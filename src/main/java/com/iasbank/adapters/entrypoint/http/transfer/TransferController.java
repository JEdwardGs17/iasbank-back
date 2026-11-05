package com.iasbank.adapters.entrypoint.http.transfer;

import com.iasbank.adapters.entrypoint.http.PageResponse;
import com.iasbank.core.domain.constants.PaginationConstants;
import com.iasbank.core.ports.input.TransferUseCase;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

@RestController
@RequestMapping("/api/transfers")
@RequiredArgsConstructor
public class TransferController {

    private final TransferUseCase transferUseCase;

    /**
     * 201 when the transfer is processed now; 200 when the reference was already processed
     * and the original result is returned.
     */
    @PostMapping
    public Mono<ResponseEntity<TransferDto.Response>> processTransfer(@Valid @RequestBody TransferDto.Request request) {
        return transferUseCase.processTransfer(request.toDomain())
                .map(result -> ResponseEntity
                        .status(result.replayed() ? HttpStatus.OK : HttpStatus.CREATED)
                        .body(TransferDto.Response.fromDomain(result.transfer())));
    }

    @GetMapping
    public Mono<PageResponse<TransferDto.Response>> getRecentTransfers(
            @RequestParam(required = false) @Min(0) Integer page,
            @RequestParam(required = false) @Min(1) @Max(PaginationConstants.MAX_PAGE_SIZE) Integer size) {
        return transferUseCase.getRecentTransfers(
                        page == null ? PaginationConstants.DEFAULT_PAGE : page,
                        size == null ? PaginationConstants.DEFAULT_PAGE_SIZE : size)
                .map(result -> PageResponse.fromDomain(result, TransferDto.Response::fromDomain));
    }

    @GetMapping("/{requestReference}")
    public Mono<ResponseEntity<TransferDto.Response>> getTransferByReference(@PathVariable String requestReference) {
        return transferUseCase.getTransferByReference(requestReference)
                .map(transfer -> ResponseEntity.ok(TransferDto.Response.fromDomain(transfer)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }
}
