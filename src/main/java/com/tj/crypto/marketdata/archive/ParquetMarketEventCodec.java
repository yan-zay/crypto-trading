package com.tj.crypto.marketdata.archive;

import com.tj.crypto.common.domain.Exchange;
import com.tj.crypto.common.domain.Instrument;
import com.tj.crypto.common.domain.MarketType;
import com.tj.crypto.common.domain.TradeSide;
import com.tj.crypto.marketdata.model.ArchivableMarketData;
import com.tj.crypto.marketdata.model.EventMetadata;
import com.tj.crypto.marketdata.model.FundingRateEvent;
import com.tj.crypto.marketdata.model.IndexPriceEvent;
import com.tj.crypto.marketdata.model.L2OrderBookEvent;
import com.tj.crypto.marketdata.model.MarkPriceEvent;
import com.tj.crypto.marketdata.model.MarketEvent;
import com.tj.crypto.marketdata.model.OpenInterestEvent;
import com.tj.crypto.marketdata.model.OrderBookLevel;
import com.tj.crypto.marketdata.model.RawMarketMessage;
import com.tj.crypto.marketdata.model.TradeEvent;
import org.apache.avro.Conversions;
import org.apache.avro.LogicalTypes;
import org.apache.avro.Schema;
import org.apache.avro.generic.GenericData;
import org.apache.avro.generic.GenericDatumWriter;
import org.apache.avro.generic.GenericRecord;
import org.apache.avro.io.Encoder;
import org.apache.avro.io.EncoderFactory;
import org.apache.parquet.avro.AvroParquetReader;
import org.apache.parquet.avro.AvroParquetWriter;
import org.apache.parquet.column.ParquetProperties;
import org.apache.parquet.conf.PlainParquetConfiguration;
import org.apache.parquet.hadoop.ParquetFileWriter;
import org.apache.parquet.hadoop.ParquetReader;
import org.apache.parquet.hadoop.ParquetWriter;
import org.apache.parquet.hadoop.metadata.CompressionCodecName;
import org.apache.parquet.io.LocalOutputFile;

import java.io.IOException;
import java.io.InputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** 真正的 Apache Parquet/Avro 列式编解码器，每种事件使用独立、版本化 schema。 */
final class ParquetMarketEventCodec {
    static final String WRITER_VERSION = "parquet-avro-1.17.1/archive-v1";
    private static final int DECIMAL_SCALE = 18;
    private static final int DECIMAL_PRECISION = 38;
    private static final PlainParquetConfiguration CONFIGURATION = new PlainParquetConfiguration();
    private static final Map<ArchiveEventType, String> SCHEMA_RESOURCES = Map.of(
            ArchiveEventType.TRADE, "/schema/marketdata/trade-v1.avsc",
            ArchiveEventType.L2_ORDER_BOOK, "/schema/marketdata/l2-v1.avsc",
            ArchiveEventType.MARK_PRICE, "/schema/marketdata/mark-price-v1.avsc",
            ArchiveEventType.INDEX_PRICE, "/schema/marketdata/index-price-v1.avsc",
            ArchiveEventType.FUNDING_RATE, "/schema/marketdata/funding-rate-v1.avsc",
            ArchiveEventType.OPEN_INTEREST, "/schema/marketdata/open-interest-v1.avsc",
            ArchiveEventType.RAW, "/schema/marketdata/raw-v1.avsc");

    private final Map<ArchiveEventType, Schema> schemas = new EnumMap<>(ArchiveEventType.class);
    private final Map<ArchiveEventType, String> fingerprints = new EnumMap<>(ArchiveEventType.class);
    private final GenericData dataModel = new GenericData();

    ParquetMarketEventCodec() {
        dataModel.addLogicalTypeConversion(new Conversions.DecimalConversion());
        SCHEMA_RESOURCES.forEach((type, resource) -> {
            byte[] bytes = resource(resource);
            schemas.put(type, new Schema.Parser().parse(new String(bytes, StandardCharsets.UTF_8)));
            fingerprints.put(type, sha256(bytes));
        });
    }

    String schemaFingerprint(ArchiveEventType type) {
        String fingerprint = fingerprints.get(type);
        if (fingerprint == null) throw unsupported(type);
        return fingerprint;
    }

    Schema schema(ArchiveEventType type) {
        Schema schema = schemas.get(type);
        if (schema == null) throw unsupported(type);
        return schema;
    }

    void write(Path target, ArchiveEventType type, List<ArchivableMarketData> events) {
        Schema schema = schema(type);
        Map<String, String> metadata = Map.of(
                "archive.schema.name", ArchiveManifest.SCHEMA_NAME,
                "archive.schema.version", ArchiveManifest.CURRENT_SCHEMA_VERSION,
                "archive.schema.sha256", schemaFingerprint(type),
                "archive.event.type", type.name());
        try (ParquetWriter<GenericRecord> writer = AvroParquetWriter.<GenericRecord>builder(new LocalOutputFile(target))
                .withSchema(schema)
                .withDataModel(dataModel)
                .withConf(CONFIGURATION)
                .withWriteMode(ParquetFileWriter.Mode.CREATE)
                .withCompressionCodec(CompressionCodecName.GZIP)
                .withRowGroupSize(16L * 1024 * 1024)
                .withPageSize(1024 * 1024)
                .withWriterVersion(ParquetProperties.WriterVersion.PARQUET_2_0)
                .withDictionaryEncoding(true)
                .withValidation(true)
                .enablePageWriteChecksum()
                .withExtraMetaData(metadata)
                .build()) {
            long row = 0;
            for (ArchivableMarketData event : events) {
                if (ArchiveEventType.from(event) != type) {
                    throw new ArchiveValidationException("MIXED_EVENT_TYPE", "batch contains mixed event types");
                }
                writer.write(toRecord(schema, type, row++, event));
            }
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ArchiveValidationException("PARQUET_WRITE_FAILED",
                    "cannot write Parquet object: " + error.getMessage(), error);
        }
    }

    List<ArchivableMarketData> read(Path source, ArchiveEventType expectedType) {
        List<ArchivableMarketData> events = new ArrayList<>();
        try (ParquetReader<GenericRecord> reader = AvroParquetReader.<GenericRecord>builder(
                        new NioParquetInputFile(source), CONFIGURATION)
                .withDataModel(dataModel)
                .disableCompatibility()
                .usePageChecksumVerification()
                .build()) {
            GenericRecord record;
            long expectedRow = 0;
            while ((record = reader.read()) != null) {
                if (!record.getSchema().equals(schema(expectedType))) {
                    throw new ArchiveValidationException("PARQUET_SCHEMA_MISMATCH",
                            "embedded Parquet/Avro schema does not match the declared event schema");
                }
                long row = (Long) record.get("row_number");
                if (row != expectedRow) {
                    throw new ArchiveValidationException("PARQUET_ROW_SEQUENCE_MISMATCH",
                            "row_number is not contiguous at row " + expectedRow);
                }
                ArchivableMarketData event = fromRecord(expectedType, record);
                String storedKey = string(record, "event_key");
                if (!storedKey.equals(eventKey(event))) {
                    throw new ArchiveValidationException("PARQUET_EVENT_KEY_MISMATCH",
                            "event identity does not match payload at row " + expectedRow);
                }
                events.add(event);
                expectedRow++;
            }
            return List.copyOf(events);
        } catch (ArchiveValidationException error) {
            throw error;
        } catch (IOException | RuntimeException error) {
            throw new ArchiveValidationException("PARQUET_READ_FAILED",
                    "cannot read or validate Parquet object: " + error.getMessage(), error);
        }
    }

    byte[] canonicalBytes(ArchiveEventType type, long row, ArchivableMarketData event) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream(512);
            Encoder encoder = EncoderFactory.get().directBinaryEncoder(output, null);
            new GenericDatumWriter<GenericRecord>(schema(type), dataModel)
                    .write(toRecord(schema(type), type, row, event), encoder);
            encoder.flush();
            return output.toByteArray();
        } catch (IOException | RuntimeException error) {
            if (error instanceof ArchiveValidationException validation) throw validation;
            throw new ArchiveValidationException("CANONICAL_ENCODING_FAILED",
                    "cannot encode canonical event bytes: " + error.getMessage(), error);
        }
    }

    static String eventKey(ArchivableMarketData event) {
        ArchiveEventType type = ArchiveEventType.from(event);
        if (event instanceof TradeEvent trade) {
            return key(type, trade.instrument(), trade.tradeId());
        }
        if (event instanceof L2OrderBookEvent book) {
            return key(type, book.instrument(), book.bookEpoch() + ":"
                    + book.firstUpdateId() + ":" + book.lastUpdateId());
        }
        if (event instanceof RawMarketMessage raw) {
            return sha256((type.name() + "|" + raw.metadata().source().name() + "|" + raw.marketType().name()
                    + "|" + raw.symbolScope() + "|" + raw.channel() + "|"
                    + raw.metadata().exchangeTimestamp() + "|" + raw.payloadSha256())
                    .getBytes(StandardCharsets.UTF_8));
        }
        MarketEvent market = (MarketEvent) event;
        EventMetadata metadata = market.metadata();
        String sourceIdentity = metadata.rawMessageId() == null || metadata.rawMessageId().isBlank()
                ? Long.toString(metadata.receivedTimestamp()) : metadata.rawMessageId().trim();
        return key(type, market.instrument(), metadata.exchangeTimestamp() + ":" + sourceIdentity);
    }

    private static String key(ArchiveEventType type, Instrument instrument, String naturalId) {
        return sha256((type.name() + "|" + instrument.exchange().name() + "|"
                + instrument.marketType().name() + "|" + instrument.symbol() + "|" + naturalId)
                .getBytes(StandardCharsets.UTF_8));
    }

    private GenericRecord toRecord(Schema schema, ArchiveEventType type, long row,
                                   ArchivableMarketData event) {
        GenericRecord record = new GenericData.Record(schema);
        record.put("row_number", row);
        record.put("event_key", eventKey(event));
        EventMetadata metadata = event.metadata();
        record.put("source", metadata.source().name());
        record.put("exchange_timestamp", metadata.exchangeTimestamp());
        record.put("received_timestamp", metadata.receivedTimestamp());
        record.put("raw_message_id", nullable(metadata.rawMessageId()));
        if (event instanceof MarketEvent market) putInstrument(record, market.instrument());

        switch (type) {
            case TRADE -> putTrade(record, (TradeEvent) event);
            case L2_ORDER_BOOK -> putBook(record, (L2OrderBookEvent) event);
            case MARK_PRICE -> {
                MarkPriceEvent mark = (MarkPriceEvent) event;
                decimal(record, "mark_price", mark.markPrice());
                nullableDecimal(record, "estimated_settlement_price", mark.estimatedSettlementPrice());
            }
            case INDEX_PRICE -> decimal(record, "index_price", ((IndexPriceEvent) event).indexPrice());
            case FUNDING_RATE -> putFunding(record, (FundingRateEvent) event);
            case OPEN_INTEREST -> putOpenInterest(record, (OpenInterestEvent) event);
            case RAW -> putRaw(record, (RawMarketMessage) event);
        }
        return record;
    }

    private void putInstrument(GenericRecord record, Instrument instrument) {
        record.put("exchange", instrument.exchange().name());
        record.put("market_type", instrument.marketType().name());
        record.put("symbol", instrument.symbol());
        record.put("base_asset", instrument.baseAsset() == null ? "" : instrument.baseAsset());
        record.put("quote_asset", instrument.quoteAsset() == null ? "" : instrument.quoteAsset());
    }

    private void putTrade(GenericRecord record, TradeEvent trade) {
        record.put("trade_id", trade.tradeId());
        decimal(record, "price", trade.price());
        decimal(record, "quantity", trade.quantity());
        decimal(record, "quote_quantity", trade.quoteQuantity());
        record.put("aggressor_side", trade.aggressorSide().name());
    }

    private void putBook(GenericRecord record, L2OrderBookEvent book) {
        record.put("book_epoch", book.bookEpoch());
        record.put("first_update_id", book.firstUpdateId());
        record.put("last_update_id", book.lastUpdateId());
        record.put("previous_update_id", book.previousUpdateId());
        record.put("checksum", book.checksum());
        record.put("snapshot", book.snapshot());
        Schema levelSchema = schema(ArchiveEventType.L2_ORDER_BOOK).getField("bids")
                .schema().getElementType();
        record.put("bids", levels(levelSchema, book.bids()));
        record.put("asks", levels(levelSchema, book.asks()));
    }

    private List<GenericRecord> levels(Schema schema, List<OrderBookLevel> levels) {
        List<GenericRecord> rows = new ArrayList<>(levels.size());
        for (OrderBookLevel level : levels) {
            GenericRecord row = new GenericData.Record(schema);
            decimal(row, "price", level.price());
            decimal(row, "quantity", level.quantity());
            rows.add(row);
        }
        return rows;
    }

    private void putFunding(GenericRecord record, FundingRateEvent funding) {
        decimal(record, "funding_rate", funding.fundingRate());
        nullableDecimal(record, "predicted_rate", funding.predictedRate());
        record.put("next_funding_time", funding.nextFundingTime());
    }

    private void putOpenInterest(GenericRecord record, OpenInterestEvent interest) {
        decimal(record, "open_interest", interest.openInterest());
        decimal(record, "open_interest_usd", interest.openInterestUsd());
    }

    private void putRaw(GenericRecord record, RawMarketMessage raw) {
        record.put("market_type", raw.marketType().name());
        record.put("symbol_scope", raw.symbolScope());
        record.put("channel", raw.channel());
        record.put("content_type", raw.contentType());
        record.put("payload", ByteBuffer.wrap(raw.payload().getBytes(StandardCharsets.UTF_8)));
        record.put("payload_sha256", raw.payloadSha256());
    }

    private ArchivableMarketData fromRecord(ArchiveEventType type, GenericRecord record) {
        EventMetadata metadata = metadata(record);
        if (type == ArchiveEventType.RAW) {
            return new RawMarketMessage(metadata, MarketType.valueOf(string(record, "market_type")),
                    string(record, "symbol_scope"), string(record, "channel"), string(record, "content_type"),
                    utf8(record.get("payload")), string(record, "payload_sha256"));
        }
        Instrument instrument = instrument(record);
        return switch (type) {
            case TRADE -> new TradeEvent(instrument, metadata, string(record, "trade_id"),
                    decimal(record, "price"), decimal(record, "quantity"), decimal(record, "quote_quantity"),
                    TradeSide.valueOf(string(record, "aggressor_side")));
            case L2_ORDER_BOOK -> new L2OrderBookEvent(instrument, metadata, string(record, "book_epoch"),
                    number(record, "first_update_id"), number(record, "last_update_id"),
                    nullableLong(record, "previous_update_id"), nullableLong(record, "checksum"),
                    (Boolean) record.get("snapshot"), levels(record, "bids"), levels(record, "asks"));
            case MARK_PRICE -> new MarkPriceEvent(instrument, metadata, decimal(record, "mark_price"),
                    nullableDecimal(record, "estimated_settlement_price"));
            case INDEX_PRICE -> new IndexPriceEvent(instrument, metadata, decimal(record, "index_price"));
            case FUNDING_RATE -> new FundingRateEvent(instrument, metadata, decimal(record, "funding_rate"),
                    nullableDecimal(record, "predicted_rate"), number(record, "next_funding_time"));
            case OPEN_INTEREST -> new OpenInterestEvent(instrument, metadata, decimal(record, "open_interest"),
                    decimal(record, "open_interest_usd"));
            case RAW -> throw new IllegalStateException("raw handled before switch");
        };
    }

    private EventMetadata metadata(GenericRecord record) {
        return new EventMetadata(Exchange.valueOf(string(record, "source")),
                number(record, "exchange_timestamp"), number(record, "received_timestamp"),
                nullableString(record.get("raw_message_id")));
    }

    private Instrument instrument(GenericRecord record) {
        return new Instrument(Exchange.valueOf(string(record, "exchange")),
                MarketType.valueOf(string(record, "market_type")), string(record, "symbol"),
                string(record, "base_asset"), string(record, "quote_asset"));
    }

    @SuppressWarnings("unchecked")
    private List<OrderBookLevel> levels(GenericRecord record, String field) {
        List<GenericRecord> values = (List<GenericRecord>) record.get(field);
        List<OrderBookLevel> levels = new ArrayList<>(values.size());
        for (GenericRecord value : values) {
            levels.add(new OrderBookLevel(decimal(value, "price"), decimal(value, "quantity")));
        }
        return levels;
    }

    private void decimal(GenericRecord record, String field, BigDecimal value) {
        Schema valueSchema = valueSchema(record.getSchema().getField(field).schema());
        BigDecimal scaled;
        try {
            scaled = value.setScale(DECIMAL_SCALE, RoundingMode.UNNECESSARY);
        } catch (ArithmeticException error) {
            throw new ArchiveValidationException("DECIMAL_SCALE_OVERFLOW",
                    field + " has more than " + DECIMAL_SCALE + " fractional digits", error);
        }
        if (scaled.precision() > DECIMAL_PRECISION) {
            throw new ArchiveValidationException("DECIMAL_PRECISION_OVERFLOW",
                    field + " exceeds precision " + DECIMAL_PRECISION);
        }
        LogicalTypes.Decimal logical = (LogicalTypes.Decimal) valueSchema.getLogicalType();
        record.put(field, new Conversions.DecimalConversion().toBytes(scaled, valueSchema, logical));
    }

    private void nullableDecimal(GenericRecord record, String field, BigDecimal value) {
        if (value == null) record.put(field, null);
        else decimal(record, field, value);
    }

    private BigDecimal decimal(GenericRecord record, String field) {
        Object encoded = record.get(field);
        if (encoded instanceof BigDecimal decimal) {
            return decimal;
        }
        Schema valueSchema = valueSchema(record.getSchema().getField(field).schema());
        LogicalTypes.Decimal logical = (LogicalTypes.Decimal) valueSchema.getLogicalType();
        return new Conversions.DecimalConversion().fromBytes(
                ((ByteBuffer) encoded).duplicate(), valueSchema, logical);
    }

    private BigDecimal nullableDecimal(GenericRecord record, String field) {
        return record.get(field) == null ? null : decimal(record, field);
    }

    private static Schema valueSchema(Schema schema) {
        if (schema.getType() != Schema.Type.UNION) return schema;
        return schema.getTypes().stream().filter(type -> type.getType() != Schema.Type.NULL).findFirst()
                .orElseThrow(() -> new ArchiveValidationException("INVALID_AVRO_SCHEMA", "union has no value type"));
    }

    private static long number(GenericRecord record, String field) {
        return ((Number) record.get(field)).longValue();
    }

    private static Long nullableLong(GenericRecord record, String field) {
        Object value = record.get(field);
        return value == null ? null : ((Number) value).longValue();
    }

    private static String string(GenericRecord record, String field) {
        Object value = record.get(field);
        if (value == null) throw new ArchiveValidationException("MISSING_PARQUET_FIELD", field + " is missing");
        return value.toString();
    }

    private static String nullableString(Object value) {
        return value == null ? null : value.toString();
    }

    private static String nullable(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static String utf8(Object value) {
        ByteBuffer bytes = ((ByteBuffer) value).duplicate();
        byte[] copy = new byte[bytes.remaining()];
        bytes.get(copy);
        return new String(copy, StandardCharsets.UTF_8);
    }

    private static byte[] resource(String resource) {
        try (InputStream input = ParquetMarketEventCodec.class.getResourceAsStream(resource)) {
            if (input == null) throw new IllegalStateException("missing schema resource " + resource);
            return input.readAllBytes();
        } catch (IOException error) {
            throw new IllegalStateException("cannot read schema resource " + resource, error);
        }
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    private static ArchiveValidationException unsupported(ArchiveEventType type) {
        return new ArchiveValidationException("UNSUPPORTED_EVENT_TYPE", "no Parquet schema for " + type);
    }
}
