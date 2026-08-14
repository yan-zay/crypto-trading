package com.tj.crypto.research.export;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

@Mapper
public interface DatasetExportMapper {
    @Insert("""
            INSERT INTO dataset_export
                (export_id, export_type, format, request_json, status, row_count,
                 data_version, schema_version, created_by, created_at_ms)
            VALUES (#{exportId}, #{exportType}, #{format}, CAST(#{requestJson} AS JSON),
                    #{status}, #{rowCount}, #{dataVersion}, #{schemaVersion},
                    #{createdBy}, #{createdAtMs})
            """)
    int insert(DatasetExportDO export);

    @Update("""
            UPDATE dataset_export SET status='COMPLETED', row_count=#{rowCount},
                checksum=#{checksum}, artifact_path=#{artifactPath}, completed_at_ms=#{completedAtMs},
                update_time=CURRENT_TIMESTAMP WHERE export_id=#{exportId}
            """)
    int complete(DatasetExportDO export);

    @Update("""
            UPDATE dataset_export SET status='FAILED', error_message=#{errorMessage},
                completed_at_ms=#{completedAtMs}, update_time=CURRENT_TIMESTAMP
            WHERE export_id=#{exportId}
            """)
    int fail(DatasetExportDO export);

    @Select("SELECT * FROM dataset_export WHERE export_id=#{exportId}")
    DatasetExportDO select(@Param("exportId") String exportId);

    @Select("SELECT * FROM dataset_export ORDER BY created_at_ms DESC LIMIT #{limit}")
    List<DatasetExportDO> selectRecent(@Param("limit") int limit);
}
