package com.tj.crypto.controller;

import com.tj.crypto.service.test.TestServiceImpl;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

import java.io.IOException;

/**
 * @Author: zay
 * @Date: 2024-03-03 16:36
 */
@Controller
@ResponseBody
@RequestMapping("/test")
@AllArgsConstructor
@Slf4j
public class TestController {

//    private final BinanceWebSocketService binanceWebSocketService;
    private final TestServiceImpl testService;

    @GetMapping("/test01")
    public String test01(@RequestParam String url) throws IOException {
        return testService.getApi(url);
    }

    @GetMapping("/test02")
    public String test02() throws Exception {
//        binanceWebSocketService.connect();
        return "222";
    }
}
