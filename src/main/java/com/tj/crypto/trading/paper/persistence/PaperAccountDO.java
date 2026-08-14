package com.tj.crypto.trading.paper.persistence;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("paper_account")
public class PaperAccountDO {
    @TableId(type = IdType.INPUT)
    private String accountId;
    private String accountName;
    private String status;
    private String baseCurrency;
    private BigDecimal initialBalance;
    private Long startedAtMs;
    private Long stoppedAtMs;
    private Long version;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
}
