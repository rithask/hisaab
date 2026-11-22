package com.hisaab.investments.controller;

import com.hisaab.common.dto.Dtos.HoldingDto;
import com.hisaab.common.dto.Dtos.PortfolioDto;
import com.hisaab.common.util.Audit;
import com.hisaab.investments.model.Holding;
import com.hisaab.investments.model.MfScheme;
import com.hisaab.investments.repository.HoldingRepository;
import com.hisaab.investments.service.HoldingService;
import com.hisaab.investments.service.HoldingService.HoldingInput;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import com.hisaab.common.exception.BadRequestException;
import java.io.IOException;
import java.util.List;
import java.util.Map;

import static com.hisaab.common.exception.NotFound.notFound;

@RestController
@RequestMapping("/api")
public class InvestmentController {
    private final HoldingService service;
    private final HoldingRepository holdings;

    public InvestmentController(HoldingService service, HoldingRepository holdings) {
        this.service = service;
        this.holdings = holdings;
    }

    @GetMapping("/holdings")
    public PortfolioDto portfolio() {
        return PortfolioDto.of(holdings.findAllByOrderByAssetTypeAscNameAsc().stream().map(HoldingDto::of).toList());
    }

    @PostMapping("/holdings")
    public HoldingDto create(@RequestBody HoldingInput in) {
        Holding h = holdings.findByAssetTypeAndSymbol(in.assetType(), in.symbol() == null ? "" : in.symbol().trim().toUpperCase())
                .orElseGet(Holding::new);
        return HoldingDto.of(service.save(h, in));
    }

    @PutMapping("/holdings/{id}")
    public HoldingDto update(@PathVariable Long id, @RequestBody HoldingInput in) {
        return HoldingDto.of(service.save(holdings.findById(id).orElseThrow(notFound("Holding")), in));
    }

    @DeleteMapping("/holdings/{id}")
    public void delete(@PathVariable Long id) {
        Audit.log("Holding deleted id={}", id);
        holdings.deleteById(id);
    }

    @PostMapping("/holdings/import")
    public HoldingService.ImportResult importCsv(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "true") boolean replace) throws IOException {
        String name = file.getOriginalFilename() == null ? "" : file.getOriginalFilename().toLowerCase();
        if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
            throw new BadRequestException("Please upload a CSV (Kite → Holdings → Download → CSV).");
        }
        return service.importCsv(file.getInputStream(), replace);
    }

    @PostMapping("/prices/refresh")
    public Map<String, Object> refresh() throws Exception {
        int stocks = service.refreshAllStocks();
        int navs = service.refreshMfNavs();
        return Map.of("stocksUpdated", stocks, "navsLoaded", navs);
    }

    @GetMapping("/mf/search")
    public List<MfScheme> searchMf(@RequestParam String q) {
        if (q.trim().length() < 3) return List.of();
        return service.searchSchemes(q);
    }
}
