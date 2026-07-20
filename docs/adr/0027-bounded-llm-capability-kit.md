# ADR 0027: Bounded LLM capability kit v1

Status: accepted; CLJ/CLJS reference provider implemented

## Decision

Kotoba text generation uses the typed capability `:llm/generate`, not direct
provider SDK imports, HTTP access, API keys, host promises, or callbacks. The
request names a host-approved model and carries bounded system and prompt text,
an output-token budget, and an integer temperature in thousandths. The result
is a nominal completion-or-error variant with bounded text and explicit usage.

Each provider instance owns an exact closed model allowlist and an injected
synchronous transport. An empty allowlist grants no model access. Credentials,
provider routing, billing metadata, retries, and secret-bearing exceptions stay
on the host. Transport exceptions are redacted into a typed error rather than
crossing the guest boundary.

Version 1 deliberately excludes streaming, tools, arbitrary multimodal blobs,
conversation state, sampling-provider extensions, and callbacks. Those require
separate versioned schemas and capability review. AOT and JIT implementations
qualify later against the same reference contract; their absence does not
remove this language-level application capability.
