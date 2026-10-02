# RateGuard - Python track. Run `make help`.
PY ?= python3
VENV ?= .venv
BIN := $(VENV)/bin

.PHONY: help install lint test test-integration coverage run build
help:
	@echo "install | lint | test | test-integration | coverage | run | build"

$(BIN)/activate:
	$(PY) -m venv $(VENV)

install: $(BIN)/activate
	$(BIN)/pip install -q -r requirements-dev.txt

lint:
	$(BIN)/ruff check app tests
	$(BIN)/ruff format --check app tests

test:
	$(BIN)/pytest

test-integration:
	$(BIN)/pytest -m integration

coverage:
	$(BIN)/pytest -m "integration or not integration" --cov=app --cov-report=term-missing --cov-report=xml --cov-fail-under=85

# Local defaults so `make run` works next to `docker compose up -d redis` (override any of them).
run: export ADMIN_TOKEN ?= local-admin-token-change-me-0123456789abcdef
run:
	$(BIN)/python -m app

build:
	docker build -f Dockerfile -t rateguard-python ../..
