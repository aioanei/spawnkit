# Koog 1.3.0: `AnthropicLLMClient` refuses tools with `ToolParameterType.AnyOf` parameters

**Where to file:** [github.com/JetBrains/koog/issues](https://github.com/JetBrains/koog/issues) · **Found:** 2026-11-23

## Environment

- Koog 1.3.0 (`prompt-executor-anthropic-client-jvm`, `agents-tools`), JVM 21, Windows 11 and Linux.
- `AnthropicLLMClient` with `KtorKoogHttpClient.Factory()`, through `MultiLLMPromptExecutor`.

## Steps to reproduce

```kotlin
val choice = ToolParameterType.AnyOf(
    arrayOf(
        ToolParameterDescriptor("number", "A number", ToolParameterType.Float),
        ToolParameterDescriptor("text", "Some text", ToolParameterType.String),
    ),
)
val tool = ToolDescriptor(
    "set_value", "Sets a value",
    requiredParameters = listOf(ToolParameterDescriptor("value", "The new value", choice)),
)
val client = AnthropicLLMClient(apiKey, AnthropicClientSettings(), KtorKoogHttpClient.Factory())
val model: LLModel = /* any Anthropic model */
MultiLLMPromptExecutor(client).execute(prompt("p") { user("Set it to 5") }, model, listOf(tool))
```

## Expected

The tool is sent with an `anyOf` JSON schema. The Anthropic Messages API accepts `anyOf` in a tool's `input_schema`.
On 2026-11-23, a direct `POST /v1/messages` with `"value": {"anyOf": [{"type": "number"}, {"type": "string"}]}` was
answered with a normal `tool_use` block.

## Actual

Nothing is sent. `execute` throws `LLMClientException` with the message:

```
Error from client: AnthropicLLMClient
AnyOf type is not supported
```

The check is in `AnthropicLLMClient`'s tool schema conversion (`instanceof ToolParameterType$AnyOf`, then the string
"AnyOf type is not supported"). In the same 1.3.0 release, the Ollama client's jar handles `ToolParameterType.AnyOf`
without this error, so `AnyOf` is a public type that one client cannot send.

## Impact

Any agent whose tools take a choice of shapes cannot use Anthropic models through Koog. Examples are a "block" that can
be one of several kinds, or a value that is a number or a text. Unit tests with a fake `PromptExecutor` do not show
the problem, because the conversion happens inside the client.

## Workaround in SpawnKit

`KoogModel` (`ai/src/dev/spawnkit/ai/KoogModel.kt`) sends choices in a flat form.
- A choice of objects becomes one object whose discriminator (such as `block`) is an enum of every choice.
- A choice of plain values becomes an object with one optional field per kind.
- The model's tool input is mapped back before use.

`KoogAnthropicWireTest` runs the real `AnthropicLLMClient` against a local stand-in for the API, so a regression shows
without a key or network.

## Suggested fix

Emit `{"anyOf": [...]}` from the descriptors of the alternatives, as other clients do. Or, if the restriction is
intended, document it and fail when the `ToolDescriptor` is built instead of at request time.
