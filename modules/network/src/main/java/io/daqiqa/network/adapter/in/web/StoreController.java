package io.daqiqa.network.adapter.in.web;

import io.daqiqa.kernel.StoreId;
import io.daqiqa.network.adapter.in.web.StoreJson.StoreDto;
import io.daqiqa.network.adapter.in.web.StoreJson.StoreListDto;
import io.daqiqa.network.adapter.in.web.StoreJson.StoreStatusUpdateDto;
import io.daqiqa.network.application.StoreService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.Objects;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/stores")
class StoreController {

    /** The StoreId schema of the contract (and of {@link StoreId}): a malformed id is a validation error. */
    private static final String STORE_ID = "^[A-Z]{3}(-[A-Z0-9]+)+$";

    private final StoreService stores;

    StoreController(StoreService stores) {
        this.stores = stores;
    }

    @GetMapping
    StoreListDto list() {
        return new StoreListDto(stores.list().stream().map(StoreDto::from).toList());
    }

    @GetMapping("/{storeId}")
    StoreDto get(@PathVariable @Pattern(regexp = STORE_ID) @Size(max = 40) String storeId) {
        return StoreDto.from(stores.get(StoreId.of(storeId)));
    }

    @PutMapping("/{storeId}/status")
    StoreDto changeStatus(
            @PathVariable @Pattern(regexp = STORE_ID) @Size(max = 40) String storeId,
            @Valid @RequestBody StoreStatusUpdateDto update) {
        return StoreDto.from(stores.changeStatus(StoreId.of(storeId), Objects.requireNonNull(update.status())));
    }
}
