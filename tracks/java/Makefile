# RateGuard - Java track. Run `make help`.
MVN ?= mvn -B

.PHONY: help install lint test test-integration coverage run build
help:
	@echo "install | lint | test | test-integration | coverage | run | build"

install:
	$(MVN) -q dependency:go-offline

lint:
	$(MVN) checkstyle:check

test:
	$(MVN) test

test-integration:
	$(MVN) verify -Pintegration

coverage:
	$(MVN) verify -Pcoverage

# Local defaults so `make run` works next to `docker compose up -d redis` (override any of them).
run: export ADMIN_TOKEN ?= local-admin-token-change-me-0123456789abcdef
run:
	$(MVN) spring-boot:run

build:
	docker build -f Dockerfile -t rateguard-java ../..
