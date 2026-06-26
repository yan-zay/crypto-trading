package com.tj.crypto.admin.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Set;

/**
 * 策略信息 DTO。
 * 描述一个已注册的策略。
 */
@Data
@Builder
public class StrategyInfoDTO {

    /** 策略名称 */
    private String name;

    /** 监听的事件类型名称集合 */
    private Set<String> listenedEvents;
}
