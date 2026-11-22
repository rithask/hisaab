package com.hisaab.investments.controller;

import com.hisaab.investments.dto.HoldingImportResponse;
import com.hisaab.investments.dto.HoldingRequest;
import com.hisaab.investments.dto.HoldingResponse;
import com.hisaab.investments.dto.PortfolioResponse;
import com.hisaab.common.util.Audit;
import com.hisaab.investments.model.Holding;
import com.hisaab.investments.model.MfScheme;
import com.hisaab.investments.repository.HoldingRepository;
import com.hisaab.investments.service.HoldingService;
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
    public PortfolioResponse portfolio() {
        return PortfolioResponse.of(holdings.findAllByOrderByAssetTypeAscNameAsc().stream().map(HoldingResponse::of).toList());
    }

    @PostMapping("/holdings")
    public HoldingResponse create(@RequestBody HoldingRequest in) {
        Holding h = holdings.findByAssetTypeAndSymbol(in.assetType(), in.symbol() == null ? "" : in.symbol().trim().toUpperCase())
                .orElseGet(Holding::new);
        return HoldingResponse.of(service.save(h, in));
    }

    @PutMapping("/holdings/{id}")
    public HoldingResponse update(@PathVariable Long id, @RequestBody HoldingRequest in) {
        return HoldingResponse.of(service.save(holdings.findById(id).orElseThrow(notFound("Holding")), in));
    }

    @DeleteMapping("/holdings/{id}")
    public void delete(@PathVariable Long id) {
        Audit.log("Holding deleted id={}", id);
        holdings.deleteById(id);
    }

    @PostMapping("/holdings/import")
    public HoldingImportResponse importCsv(@RequestParam("file") MultipartFile file,
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
