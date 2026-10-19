package io.github.benjaminursu.bankimport;

import org.springframework.boot.SpringApplication;

public class TestBankImportApplication {

    public static void main(String[] args) {
        SpringApplication.from(BankImportApplication::main).with(TestcontainersConfiguration.class).run(args);
    }

}
