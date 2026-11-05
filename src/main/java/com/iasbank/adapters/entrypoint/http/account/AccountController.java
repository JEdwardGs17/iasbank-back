package com.iasbank.adapters.entrypoint.http.account;

import com.iasbank.core.ports.input.AccountUseCase;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@RequestMapping("/api/accounts")
@RequiredArgsConstructor
public class AccountController {

    private final AccountUseCase accountUseCase;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public Mono<AccountDto.Response> createAccount(@Valid @RequestBody AccountDto.CreateRequest request) {
        return accountUseCase.createAccount(request.toDomain())
                .map(AccountDto.Response::fromDomain);
    }

    @GetMapping
    public Mono<List<AccountDto.Response>> getAllAccounts() {
        return accountUseCase.getAllAccounts()
                .map(AccountDto.Response::fromDomain)
                .collectList();
    }

    @GetMapping("/{id}")
    public Mono<ResponseEntity<AccountDto.Response>> getAccountById(@PathVariable Long id) {
        return accountUseCase.getAccountById(id)
                .map(a -> ResponseEntity.ok(AccountDto.Response.fromDomain(a)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @GetMapping("/number/{accountNumber}")
    public Mono<ResponseEntity<AccountDto.Response>> getAccountByNumber(@PathVariable String accountNumber) {
        return accountUseCase.getAccountByNumber(accountNumber)
                .map(a -> ResponseEntity.ok(AccountDto.Response.fromDomain(a)))
                .defaultIfEmpty(ResponseEntity.notFound().build());
    }

    @PostMapping("/deposit")
    public Mono<AccountDto.Response> deposit(@Valid @RequestBody AccountDto.TransactionRequest request) {
        return accountUseCase.deposit(request.accountNumber(), request.amount())
                .map(AccountDto.Response::fromDomain);
    }

    @PostMapping("/withdraw")
    public Mono<AccountDto.Response> withdraw(@Valid @RequestBody AccountDto.TransactionRequest request) {
        return accountUseCase.withdraw(request.accountNumber(), request.amount())
                .map(AccountDto.Response::fromDomain);
    }

    @PutMapping("/{accountNumber}/daily-limit")
    public Mono<AccountDto.Response> updateDailyLimit(@PathVariable String accountNumber,
                                                      @Valid @RequestBody AccountDto.DailyLimitRequest request) {
        return accountUseCase.updateDailyLimit(accountNumber, request.dailyLimit())
                .map(AccountDto.Response::fromDomain);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public Mono<Void> deleteAccount(@PathVariable Long id) {
        return accountUseCase.deleteAccount(id);
    }
}
