package com.tj.crypto.pojo.dto;

import lombok.Data;

import java.util.List;

/**
 * @Author zay
 * @Date 2025/10/13 17:10
 */
@Data
public class CgResultDTO<T> {

    private String channel;
    private List<T> data;
    private Long time;
}
