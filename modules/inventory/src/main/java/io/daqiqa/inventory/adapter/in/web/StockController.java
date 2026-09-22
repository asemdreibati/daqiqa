package io.daqiqa.inventory.adapter.in.web;

import io.daqiqa.inventory.adapter.in.web.StockMessages.StockAdjustmentRequest;
import io.daqiqa.inventory.adapter.in.web.StockMessages.StockLevelDto;
import io.daqiqa.inventory.adapter.in.web.StockMessages.StockLevelsDto;
import io.daqiqa.inventory.application.StockService;
import io.daqiqa.inventory.domain.StoreStock;
import io.daqiqa.kernel.Sku;
import io.daqiqa.kernel.StoreId;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/stores/{storeId}/stock")
class StockController {

    static final int MAX_SKUS_PER_REQUEST = 100;

    private final StockService stock;

    StockController(StockService stock) {
        this.stock = stock;
    }

    /** Levels for every requested SKU, in request order, zeros for SKUs the store does not stock. */
    @GetMapping
    StockLevelsDto levels(
            @PathVariable String storeId, @RequestParam @Size(min = 1, max = MAX_SKUS_PER_REQUEST) List<String> sku) {
        StoreId store = Identifiers.storeId(storeId);
        List<Sku> requested = sku.stream().map(Identifiers::sku).distinct().toList();
        StoreStock levels = stock.levels(store, requested);
        return new StockLevelsDto(
                store.value(),
                requested.stream().map(levels::level).map(StockLevelDto::from).toList());
    }

    @PostMapping("/adjustments")
    StockLevelDto adjust(@PathVariable String storeId, @Valid @RequestBody StockAdjustmentRequest request) {
        return StockLevelDto.from(stock.adjust(request.toCommand(Identifiers.storeId(storeId))));
    }
}
