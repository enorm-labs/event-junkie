# A local LLM with Ollama

[Ollama](https://ollama.com/) runs an open-weights model on your own machine. The model reads your code locally, and no prompt leaves the machine. This
document covers installation, model choice, updates, and use in the terminal and in IntelliJ IDEA.

A local model is an option, not a requirement. Nothing in the build, the tests or CI uses it.

## The short version

```bash
brew install ollama && brew services start ollama   # install, and run the server at login
curl "whatmodel.sh/?mac=$(sysctl -n hw.memsize)"     # the best models for this Mac's memory
ollama pull qwen3.6:35b-a3b-coding                   # download a model
ollama run qwen3.6:35b-a3b-coding "Explain AGENTS.md" # test it in the terminal
brew upgrade ollama && brew services restart ollama  # update Ollama
ollama pull <model>                                  # update a model; it downloads only a changed model
ollama launch claude --model qwen3.6:35b-a3b-coding  # Claude Code on the local model
```

- IntelliJ IDEA connects to `http://localhost:11434` through the AI Assistant plugin or the Continue plugin. See
  [Use the model in IntelliJ IDEA](#use-the-model-in-intellij-idea).
- After `brew upgrade ollama`, restart the service. Until you do, the old server version continues to run.

## 1. Install Ollama

1. Install the formula. The Homebrew formula runs the server as a background service, without the menu bar app:

    ```bash
    brew install ollama
    brew services start ollama
    ```

2. Make sure that the server answers:

    ```bash
    ollama --version          # prints the client version, and a warning if the server version is different
    curl -s localhost:11434   # prints "Ollama is running"
    ```

The server listens on `localhost:11434` only. Do not set `OLLAMA_HOST=0.0.0.0`, because that makes the model available to every device on the network.

## 2. Choose a model

The correct model depends on the unified memory of the Mac. [whatmodel.sh](https://whatmodel.sh/) ranks the models that fit:

```bash
curl "whatmodel.sh/?mac=$(sysctl -n hw.memsize)"               # the top ten, with the ollama command for each
curl "whatmodel.sh/?mac=$(sysctl -n hw.memsize)&format=json"   # the same, with the measured speed and memory
```

Use these rules to choose from the list:

- **Speed is more important than score in the IDE.** A chat answer at 8 tokens per second is too slow to use. A mixture-of-experts model (`35B-A3B`: 35 billion
  parameters, 3 billion active) is approximately five times faster than a dense 27B model of the same quality.
- **Keep 15 GB free for the IDE, Gradle, Docker and the browser.** macOS gives the GPU approximately 75% of the memory. A 64 GB Mac has approximately 48 GB for
  a model. The model and its context cache must both fit.
- **Get the tag from the Ollama library.** Not every model on whatmodel.sh is in the Ollama library. A command that starts with `llama-server` or `mlx_lm` does
  not work with Ollama. Look at `https://ollama.com/library/<model>/tags` for the available tags and their sizes.

| Mac memory | Recommended model        | Download | Why                                               |
| ---------- | ------------------------ | -------- | ------------------------------------------------- |
| 32 GB      | `qwen3.6:35b-a3b-coding` | 23 GB    | Fast, and it fits if few other applications run   |
| 48–64 GB   | `qwen3.6:35b-a3b-coding` | 23 GB    | Fast, with space for a large context window       |
| 48–64 GB   | `qwen3.8:27b`            | 18 GB    | Better answers, approximately five times slower   |
| 96 GB +    | `qwen3-coder-next:q8_0`  | 85 GB    | The largest coding model that the memory can hold |

This table is correct on the date of the last change to this file. New models are released each month, so run `whatmodel.sh` again before you download.

## 3. Keep Ollama and the models up to date

Ollama and each model update separately.

1. Update Ollama:

    ```bash
    brew upgrade ollama
    brew services restart ollama
    ```

2. Update a model. `ollama pull` compares the local digest with the registry. It downloads the model again only when the publisher changed it:

    ```bash
    ollama list                     # the models on this machine
    ollama pull qwen3.6:35b-a3b-coding
    ```

3. Remove a model that you do not use any more. Each model uses between 15 GB and 90 GB of disk:

    ```bash
    ollama rm <model>
    ```

A new model version usually has a new name (`qwen3.6`, then `qwen3.8`), not a new digest on the old name. `ollama pull` does not move you to it. Run
`whatmodel.sh` again to find it.

## Use the model in the terminal

`ollama run <model>` starts a chat. The model cannot read the files in the repository, so you must paste the code into the chat.

`ollama launch` starts a coding agent on the local model. The agent reads the repository, edits files and runs commands:

```bash
ollama launch claude --model qwen3.6:35b-a3b-coding     # Claude Code
ollama launch opencode --model qwen3.6:35b-a3b-coding   # OpenCode
ollama launch                                           # a menu of all integrations: codex, qwen, cline and more
```

### Claude Code

Claude Code reads `AGENTS.md`, so it follows the rules of this project. The answers of a local model are less good than those of a hosted Claude model.
Long tasks are slower.

Claude Code can show `Model "<model>" isn't recognized · context assumed to be 200k tokens`. Make sure that this assumption is safe:

```bash
ollama ps   # the CONTEXT column shows the context window of each loaded model
```

If CONTEXT is 200000 or more, ignore the warning. Claude Code compacts the conversation before Ollama reaches its limit. If CONTEXT is less, Ollama can drop
the start of the conversation without a warning. Then make a variant of the model with a larger window:

```bash
printf 'FROM qwen3.6:35b-a3b-coding\nPARAMETER num_ctx 65536\n' > "$TMPDIR/Modelfile"
ollama create qwen3.6-64k -f "$TMPDIR/Modelfile"
ollama launch claude --model qwen3.6-64k
```

The variant uses the same weights, so it uses no more disk. A larger window uses more memory while the model runs.

Do not edit `~/Library/LaunchAgents/homebrew.mxcl.ollama.plist` for this. `brew services` writes that file again from the formula.

### OpenCode

[OpenCode](https://opencode.ai/) is an open-source coding agent for the terminal. It reads `AGENTS.md`, so it also follows the rules of this project.

1. Install OpenCode:

    ```bash
    brew install opencode
    ```

2. Start it on the local model from the repository root:

    ```bash
    ollama launch opencode --model qwen3.6:35b-a3b-coding
    ```

`ollama launch` gives OpenCode an inline configuration for the model. It does not change `~/.config/opencode/opencode.json`.

To start `opencode` without `ollama launch`, add Ollama as a provider in `~/.config/opencode/opencode.json`:

```json
{
    "$schema": "https://opencode.ai/config.json",
    "model": "ollama/qwen3.6:35b-a3b-coding",
    "small_model": "ollama/qwen3.6:35b-a3b-coding",
    "provider": {
        "ollama": {
            "npm": "@ai-sdk/openai-compatible",
            "name": "Ollama (local)",
            "options": { "baseURL": "http://localhost:11434/v1" },
            "models": { "qwen3.6:35b-a3b-coding": { "name": "Qwen 3.6 (local)" } }
        }
    }
}
```

`model` makes the local model the default. Without it, OpenCode starts on its own default model. `small_model` is the model for small tasks, for example
session titles. Without it, these tasks can go to a hosted model. Then run `opencode`. `opencode models` lists each model that OpenCode can use.

OpenCode needs a context window of at least 64,000 tokens. Use `ollama ps` to make sure, as for Claude Code. If tool calls fail, the context window is
possibly too small.

## Use the model in IntelliJ IDEA

Two plugins connect to Ollama. AI Assistant is from JetBrains, and its use may need a JetBrains AI licence. Continue is open source and free.

### JetBrains AI Assistant

The JetBrains AI Assistant plugin connects to Ollama.
[JetBrains: use local models](https://www.jetbrains.com/help/ai-assistant/use-custom-models.html) has the full procedure.

1. Install the **JetBrains AI Assistant** plugin: **Settings | Plugins | Marketplace**.
2. Make sure that Ollama runs and that one model is downloaded.
3. Go to **Settings | Tools | AI Assistant | Providers & API keys**.
4. In **Third-party AI providers**, select **Ollama**.
5. Set the URL to `http://localhost:11434`, then click **Test Connection**.
6. In **Models Assignment**, select the local model for **Core features** and **Instant helpers**. Without this step, only AI Chat uses the local model.
7. Set the context window. 32,000 tokens is a good start for a 64 GB Mac. A larger window uses more memory.
8. Click **Apply**.

Then select the local model in the model list of the AI Chat tool window.

**The limits of a local model in AI Assistant:**

- Agents cannot use a local model.
- AI Assistant does not call MCP tools when it uses a local model.
- Inline code completion uses JetBrains models by default. The AI Completion settings accept an **OpenAI Compatible** provider. Ollama supplies an
  OpenAI-compatible API at `http://localhost:11434/v1`, so this may work. This project did not test it.

### Continue

[Continue](https://docs.continue.dev/) supplies chat, edit and autocomplete. This project did not test it.

1. Install the **Continue** plugin: **Settings | Plugins | Marketplace**.
2. Write `~/.continue/config.yaml`:

    ```yaml
    name: Local
    version: 1.0.0
    schema: v1
    models:
        - name: Qwen 3.6 (local)
          provider: ollama
          model: qwen3.6:35b-a3b-coding
          roles: [chat, edit, apply, autocomplete]
    ```

3. Open the Continue tool window, then select **Qwen 3.6 (local)**.

If autocomplete is too slow, give the `autocomplete` role to a smaller model.

## Background and history

The `brew` formula is used, not the Ollama app, because the formula runs as a `launchd` service without a login item. Its environment variables are in
`~/Library/LaunchAgents/homebrew.mxcl.ollama.plist`. `OLLAMA_FLASH_ATTENTION=1` and `OLLAMA_KV_CACHE_TYPE=q8_0` halve the memory of the context cache.
