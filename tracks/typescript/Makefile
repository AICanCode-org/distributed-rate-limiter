# RateGuard - TypeScript track. Run `make help`.
.PHONY: help install lint test test-integration coverage run build
help:
	@echo "install | lint | test | test-integration | coverage | run | build"

install:
	npm ci --no-audit --no-fund

lint:
	npm run -s lint

test:
	npm test

test-integration:
	npm run test:integration

coverage:
	npm run coverage

# Local defaults so `make run` works next to `docker compose up -d redis` (override any of them).
run: export ADMIN_TOKEN ?= local-admin-token-change-me-0123456789abcdef
run:
	npm run dev

build:
	npm run build
	docker build -f Dockerfile -t rateguard-typescript ../..
