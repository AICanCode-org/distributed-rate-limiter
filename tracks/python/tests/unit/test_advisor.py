"""M5: the AI policy advisor: statistics by code, advice by rules (offline) or a validated LLM answer."""

import json
from datetime import UTC, datetime

import httpx
import pytest

from app.advisor import (
    SYSTEM_PROMPT,
    Advice,
    AdvisorService,
    LlmAdvisor,
    LlmClient,
    RuleAdvisor,
    parse_advice,
    stats_json,
    summarize,
)
from app.models import Policy, Tenant, UsagePoint

M0 = 1_790_933_400_000 // 60_000  # 2026-10-02T09:30Z as an epoch minute
TB10 = Policy("TOKEN_BUCKET", 10, 60)


def pts(*counts) -> list[UsagePoint]:
    return [UsagePoint(M0 + i, a, d) for i, (a, d) in enumerate(counts)]


def tenant(policy=TB10) -> Tenant:
    now = datetime(2026, 10, 2, 9, 30, tzinfo=UTC)
    return Tenant("acme", "Acme Corp", policy, "h", "rg_000000", now, now)


def advise(policy, points) -> Advice:
    return RuleAdvisor().advise(policy, summarize(policy, points))


def test_summarize_computes_integer_statistics():
    s = summarize(TB10, pts((0, 0), (3, 0), (1, 1), (6, 4), (0, 0)))
    assert (s.minutes, s.allowed, s.denied, s.total, s.denied_percent) == (5, 10, 5, 15, 33)
    assert (s.peak, s.peak_minute, s.peak_denied) == (10, M0 + 3, 4)
    assert (s.median, s.p90, s.active_minutes, s.capacity_per_minute) == (3, 10, 3, 10)


def test_summarize_without_traffic():
    s = summarize(Policy("SLIDING_WINDOW", 1, 3600), pts((0, 0), (0, 0)))
    assert (s.total, s.peak, s.peak_minute, s.median, s.p90, s.denied_percent) == (0, 0, None, 0, 0, 0)
    assert s.capacity_per_minute == 0  # 1 per hour rounds down to 0 per minute


def test_stats_json_is_the_api_shape():
    s = summarize(TB10, pts((0, 0), (6, 4)))
    assert stats_json(s) == {
        "minutes": 2,
        "allowed": 6,
        "denied": 4,
        "deniedPercent": 40,
        "peakPerMinute": 10,
        "peakMinute": "2026-10-02T09:31:00.000Z",
        "peakMinuteDenied": 4,
        "medianPerMinute": 10,
        "p90PerMinute": 10,
        "activeMinutes": 1,
        "capacityPerMinute": 10,
    }


def test_rule_no_traffic_keeps_the_policy():
    assert advise(TB10, pts(*[(0, 0)] * 5)) == Advice(
        "KEEP", None, "No requests in the last 5 minutes: keep the current policy."
    )


def test_rule_a_spike_is_an_anomaly_to_investigate():
    a = advise(TB10, pts(*[(2, 0)] * 7, (10, 30), (2, 0), (2, 0)))
    assert a == Advice(
        "INVESTIGATE",
        None,
        "Spike at 09:37 UTC: 40 requests in one minute, 20x the usual 2. 30 of the 30 denials happened in that "
        "minute, which looks like a retry storm or abuse, not growth. Keep the limit and check the client.",
    )


def test_rule_steady_demand_above_the_limit_raises_it_with_headroom():
    a = advise(TB10, pts(*[(10, 2)] * 10))
    assert a == Advice(
        "RAISE_LIMIT",
        15,
        "16% of requests were denied (20 of 120). Steady demand is about 12 per minute (90th percentile) against "
        "a capacity of 10 per minute. Raise the limit to 15 per 60 s (20% headroom).",
    )


def test_rule_raise_scales_to_the_window_and_is_capped_at_ten_times():
    assert advise(Policy("TOKEN_BUCKET", 100, 3600), pts(*[(1, 2)] * 10)).limit == 216  # 3/min * 60 * 1.2
    assert advise(Policy("TOKEN_BUCKET", 1, 60), pts(*[(1, 49)] * 10)).limit == 10


def test_rule_raise_is_skipped_at_the_maximum_limit():
    a = advise(Policy("TOKEN_BUCKET", 100_000, 60), pts(*[(100_000, 30_000)] * 3))
    assert a.action == "KEEP"


def test_rule_a_limit_far_above_any_use_can_be_lowered():
    a = advise(Policy("SLIDING_WINDOW", 1000, 60), pts(*[(5, 0)] * 12))
    assert a == Advice(
        "LOWER_LIMIT",
        10,
        "Peak demand was 5 per minute, under a quarter of the capacity of 1000 per minute. 10 per 60 s (twice the "
        "peak) still fits and limits the damage if the key leaks.",
    )


def test_rule_lowering_needs_enough_history():
    assert advise(Policy("SLIDING_WINDOW", 1000, 60), pts(*[(5, 0)] * 9)).action == "KEEP"


def test_rule_otherwise_keep():
    a = advise(TB10, pts(*[(6, 0)] * 12))
    assert a == Advice(
        "KEEP",
        None,
        "0% denied and a peak of 6 per minute against a capacity of 10 per minute: the current policy fits.",
    )


def test_parse_advice_accepts_a_valid_answer_and_code_fences():
    assert parse_advice('{"action":"RAISE_LIMIT","limit":20,"summary":"Steady  demand."}', TB10) == Advice(
        "RAISE_LIMIT", 20, "Steady demand."
    )
    fenced = '```json\n{"action": "INVESTIGATE", "limit": null, "summary": "Spike."}\n```'
    assert parse_advice(fenced, TB10) == Advice("INVESTIGATE", None, "Spike.")
    assert parse_advice('{"action": "KEEP", "summary": "Fine."}', TB10).limit is None


@pytest.mark.parametrize(
    "answer",
    [
        "not json",
        "[1, 2]",
        '{"action": "DELETE_TENANT", "summary": "x"}',
        '{"action": "RAISE_LIMIT", "limit": true, "summary": "x"}',
        '{"action": "RAISE_LIMIT", "limit": 10, "summary": "x"}',
        '{"action": "RAISE_LIMIT", "limit": 101, "summary": "x"}',
        '{"action": "LOWER_LIMIT", "limit": 10, "summary": "x"}',
        '{"action": "LOWER_LIMIT", "limit": 0, "summary": "x"}',
        '{"action": "KEEP", "limit": 12, "summary": "x"}',
        '{"action": "KEEP", "summary": "   "}',
        '{"action": "KEEP", "summary": 5}',
        '{"action": "KEEP", "summary": "' + "x" * 401 + '"}',
        '{"action": "RAISE_LIMIT", "limit": 15.5, "summary": "x"}',
    ],
)
def test_parse_advice_rejects_anything_unexpected(answer):
    with pytest.raises((ValueError, TypeError)):
        parse_advice(answer, TB10)


class FakeChat:
    def __init__(self, answer=None, error=None):
        self.answer, self.error, self.calls = answer, error, []

    def chat(self, system, user, max_tokens):
        self.calls.append((system, user, max_tokens))
        if self.error:
            raise self.error
        return self.answer


def test_llm_advisor_sends_only_numbers_inside_stats_tags():
    chat = FakeChat('{"action": "KEEP", "limit": null, "summary": "Looks fine."}')
    s = summarize(TB10, pts((3, 0), (4, 0)))
    assert LlmAdvisor(chat).advise(tenant(), s) == Advice("KEEP", None, "Looks fine.")
    system, user, max_tokens = chat.calls[0]
    assert system == SYSTEM_PROMPT and max_tokens == 300
    assert user.startswith("<stats>") and user.endswith("</stats>")
    facts = json.loads(user[len("<stats>") : -len("</stats>")])
    assert facts == {"policy": {"algorithm": "TOKEN_BUCKET", "limit": 10, "windowSeconds": 60}, "stats": stats_json(s)}
    assert "Acme" not in user and "rg_" not in user


def test_service_uses_the_llm_when_it_answers_well():
    svc = AdvisorService(RuleAdvisor(), LlmAdvisor(FakeChat('{"action": "INVESTIGATE", "summary": "Odd spike."}')))
    r = svc.advise(tenant(), summarize(TB10, pts((1, 0))))
    assert (r.provider, r.fallback, r.advice.action) == ("llm", False, "INVESTIGATE")


@pytest.mark.parametrize("chat", [FakeChat(error=httpx.ConnectTimeout("down")), FakeChat("I think you should relax.")])
def test_service_falls_back_to_the_rules_on_any_llm_failure(chat):
    r = AdvisorService(RuleAdvisor(), LlmAdvisor(chat)).advise(tenant(), summarize(TB10, pts((0, 0))))
    assert (r.provider, r.fallback, r.advice.action) == ("rules", True, "KEEP")


def test_service_offline_by_default():
    r = AdvisorService(RuleAdvisor()).advise(tenant(), summarize(TB10, pts((0, 0))))
    assert (r.provider, r.fallback) == ("rules", False)


def test_llm_client_speaks_openai_chat_completions():
    seen = {}

    def handler(request: httpx.Request) -> httpx.Response:
        seen.update(url=str(request.url), auth=request.headers.get("authorization"), body=json.loads(request.content))
        return httpx.Response(200, json={"choices": [{"message": {"content": "hi"}}]})

    client = LlmClient("http://llm.test/v1", "tiny", "secret-key", 1000, transport=httpx.MockTransport(handler))
    assert client.chat("sys", "user", 50) == "hi"
    assert seen["url"] == "http://llm.test/v1/chat/completions" and seen["auth"] == "Bearer secret-key"
    assert seen["body"]["model"] == "tiny" and seen["body"]["temperature"] == 0 and seen["body"]["max_tokens"] == 50
    assert [m["role"] for m in seen["body"]["messages"]] == ["system", "user"]


def test_llm_client_raises_on_http_errors_and_empty_answers():
    for response in [httpx.Response(500), httpx.Response(200, json={"choices": [{"message": {"content": " "}}]})]:
        client = LlmClient(
            "http://llm.test/v1", "tiny", None, 1000, transport=httpx.MockTransport(lambda r, x=response: x)
        )
        with pytest.raises((httpx.HTTPStatusError, ValueError)):
            client.chat("sys", "user", 10)
