# Instalação e replicação

## Pré-requisitos

- Windows x64, sessão interativa, .NET SDK 8, JDK 17, PowerShell 7 e Android SDK Platform 35 com Build Tools 35.0.0.
- Android 11 ou superior, cabo USB de dados e Depuração USB. Baixe os [Platform-Tools oficiais](https://developer.android.com/tools/releases/platform-tools).
- Para voz local, reserve alguns GiB para executáveis e modelos. A referência usa i5-9300H, 24 GiB de RAM e GTX 1650. O Ollama foi validado por CPU. O painel básico independe dos componentes de voz.

## Compilar e conectar

1. Clone o repositório privado e abra a pasta. Em `.toolchain/`, coloque o JDK em `jdk-17.../`, o SDK em `platforms/android-35/android.jar` e as Build Tools em `build-tools/35.0.0/`. O script procura os executáveis nessas árvores.
2. Extraia o Platform-Tools de modo que `adb.exe` esteja diretamente em `C:\RianDeck\platform-tools`, ou passe seu caminho nos parâmetros.
3. Execute os comandos abaixo. Substitua o serial pelo retornado pelo ADB. O estado esperado é `device`. Se aparecer `unauthorized`, desbloqueie o telefone e confirme a chave RSA no Android.

```powershell
$adb = 'C:\RianDeck\platform-tools\adb.exe'
& $adb devices -l
$serial = 'SERIAL_DO_APARELHO'
.\Build.ps1
Start-Process -FilePath (Resolve-Path '.\out\agent\RianDeck.Agent.exe') -ArgumentList "--deck-serial=$serial", ('"--deck-adb=' + $adb + '"') -WindowStyle Hidden
.\Connect.ps1 -Serial $serial -Adb $adb -Install -Open
.\Diagnose.ps1 -Serial $serial -Adb $adb
```

A primeira compilação gera `out/agent/pairing.key`, `out/prototype.jks` e `out/RianDeckOS.apk`. Guarde assinatura e pareamento localmente para atualizar o mesmo aparelho. O keystore do script se destina a protótipo. Para distribuição, defina um fluxo de assinatura próprio. Use `Build.ps1 -AndroidOnly` para mudança só no APK. Para recompilar o agente no mesmo diretório, pare a instância correspondente e reinicie-a após o build. Preserve `out/agent/studio/` para manter a configuração.

## Assistente local

Escolha um diretório, por exemplo `D:\RianDeckVoice`. Configure `deck-local-voice-root` em `out/agent/appsettings.json`. O registro de inicialização grava os parâmetros USB, então persistir configurações extras nesse arquivo permite reaproveitá-las após o login.

```json
{
  "deck-local-voice-root": "D:\\RianDeckVoice",
  "deck-ollama-model": "qwen3:1.7b",
  "deck-assistant-actions": true
}
```

| Caminho relativo | Conteúdo |
| --- | --- |
| `ollama/ollama.exe` | Ollama portátil Windows x64 com as bibliotecas da distribuição |
| `ollama-models/` | Pasta que `OLLAMA_MODELS` deve apontar |
| `whisper/whisper-cli.exe` | Whisper.cpp para Windows x64 e as DLLs correspondentes |
| `models/ggml-base.bin` | Modelo multilíngue Base do Whisper |
| `models/ggml-small-q5_1.bin` | Modelo opcional para segunda leitura com baixa confiança |
| `piper-runtime/` | Piper TTS e dependências Python |
| `python-path.txt` | Caminho absoluto do Python |
| `voices/` | Vozes `pt_BR-edresson-low.onnx` e `pt_BR-faber-medium.onnx`, cada qual com seu `.onnx.json` |

A referência usou Ollama 0.40.16, Whisper.cpp 1.8.3, Piper TTS 1.8.0 e Python 3.14 x64. Baixe os executáveis e modelos nas publicações oficiais listadas abaixo. Extraia os executáveis nas pastas da tabela. Crie o diretório de voz antes de gravar a configuração.

```powershell
$voz = 'D:\RianDeckVoice'
python -m pip install --target "$voz\piper-runtime" "piper-tts==1.8.0"
(python -c "import sys; print(sys.executable)").Trim() | Set-Content "$voz\python-path.txt" -Encoding utf8NoBOM
$env:OLLAMA_HOST = '127.0.0.1:11434'
$env:OLLAMA_MODELS = "$voz\ollama-models"
$env:OLLAMA_NO_CLOUD = '1'
Start-Process "$voz\ollama\ollama.exe" -ArgumentList serve -WindowStyle Hidden
& "$voz\ollama\ollama.exe" pull qwen3:1.7b
```

Reinicie o agente depois de gravar `appsettings.json`. A internet é necessária para obter os componentes. O processamento de voz funciona localmente depois dos downloads. Em **Assistente**, conceda permissão e use dois toques por pergunta.

Fontes: [Ollama](https://github.com/ollama/ollama/releases), [Qwen3](https://ollama.com/library/qwen3:1.7b), [Whisper.cpp](https://github.com/ggml-org/whisper.cpp/releases), [modelos Whisper](https://huggingface.co/ggerganov/whisper.cpp), [Piper](https://github.com/OHF-Voice/piper1-gpl), [vozes Piper](https://huggingface.co/rhasspy/piper-voices).

## Configurações pessoais e institucionais

No appsettings.json local do agente, use deck-voice-name e deck-voice-pronunciation para personalizar a saudação. Use deck-serpro-url para configurar o endereço HTTPS autorizado por sua instituição. Guarde esses valores somente na instalação local. Não versione esse arquivo.
