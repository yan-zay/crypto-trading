package com.tj.crypto.admin;

import com.tj.crypto.observability.slo.TradingSloService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Current SLO/error-budget view and durable history. */
@RestController
@RequestMapping("/api/admin/slo")
@RequiredArgsConstructor
public class SloAdminController {
    private final TradingSloService sloService;

    @GetMapping("/current")
    public Object current() {
        return sloService.current();
    }

    @GetMapping("/history")
    public Object history(@RequestParam(required = false) String name,
                          @RequestParam(defaultValue = "200") int limit) {
        return sloService.history(name, limit);
    }
}
