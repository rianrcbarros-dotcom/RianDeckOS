# Arquitetura e integrações

~~~mermaid
flowchart LR
  And["Android Java"] <-->|"ADB reverse · USB · porta 48627"| Agent["Agente ASP.NET no Windows"]
  And -->|"Clima por cidade"| Met["Open-Meteo"]
  Agent -->|"Versículo e arte"| Bible["Biblia.com"]
  Agent -->|"Atalhos, macros, GSMTC e desempenho"| Win["Sessão interativa Windows"]
  Agent -->|"Transcrição pt-BR"| Whisper["Whisper.cpp"]
  Agent -->|"Pergunta e contexto curto · loopback 11434"| Ollama["Ollama + Qwen3"]
  Agent -->|"Síntese pt-BR"| Piper["Piper"]
  Agent -->|"Análise com confirmação"| Codex["Codex CLI"]
  Editor["Studio no navegador"] <-->|"Credencial de editor"| Agent
~~~

| Bloco | Implementação e comportamento |
| --- | --- |
| Android | `DeckActivity.java` monta as cinco abas. `VoiceDeck.java` controla a captura manual e `DeckVoiceSocket.java` transmite PCM. `MemeStatus.java` controla GIFs e estados. |
| USB e início | `Connect.ps1` instala e abre o painel. O agente verifica e recupera a porta 48627 no ADB. `DeckAutostart` registra o executável no HKCU ao ativar a opção de login. |
| Agente | `Program.cs` limita o serviço a 127.0.0.1. As rotas do Deck validam a chave pareada. O Studio usa credencial própria. O catálogo do agente define quais ações podem ser executadas. |
| Studio e automações | `DeckStudio.cs` e `Editor.html` mantêm páginas, perfis, favoritos de macros e histórico. O perfil automático pode mudar conforme o processo em primeiro plano. |
| Monitoramento | Win32 para CPU, RAM, processos e disco, contadores Windows para GPU e tráfego de rede, e `nvidia-smi` quando disponível para temperatura. FPS aparece sem medição. Rede mostra volume de tráfego. |
| Player | GSMTC fornece capa, título, posição e controles conforme o aplicativo Windows. |
| Voz | Após o segundo toque, `LocalVoiceSession` recorta e encaminha áudio para Whisper. `VoiceIntent.cs` classifica os atalhos de voz sem usar o modelo de conversa. Qwen responde perguntas gerais. Piper fala via saída padrão do Windows ou mídia do Android, conforme a preferência. |
| Codex | O CLI usa o login oficial. A análise precisa de caminho de repositório autorizado, exige confirmação e roda em sandbox somente leitura. |
| Clima e versículo | Open-Meteo consulta a cidade escolhida no Android. O agente consulta a fonte de versículos quando disponível. |

## Contratos do agente

| Rota | Uso |
| --- | --- |
| `GET /v1/status` | CPU, RAM, GPU, armazenamento, rede, temperatura, processos e microfone |
| `GET /v1/deck` e `POST /v1/profile` | Perfil, páginas, macros e troca de perfil |
| `POST /v1/action/{id}` e `POST /v1/macro/{id}` | Comandos registrados e execução de macro |
| `GET /v1/media` e `GET /v1/media/cover` | Sessão de mídia e capa |
| `GET /v1/verse` | Versículo do dia |
| `GET /v1/startup` e `POST /v1/startup?enabled=true` | Estado e ajuste da inicialização |
| `GET /editor` e `/studio/config` | Studio e configuração de perfis |
| `WS /v1/assistant/realtime` | Voz local. O caminho histórico usa realtime, o processamento é local. |

O WebSocket recebe a configuração, depois `start_capture`, PCM de 24 kHz mono e `submit_capture`. A alternativa `pc_input` seleciona a entrada padrão Windows apenas no turno. A resposta publica legenda e estado. Se o Android for escolhido como saída, recebe PCM binário. A saída padrão é a do Windows.

## Dados locais

O agente guarda o pareamento, a credencial do editor, a configuração e o histórico em `out/agent/`. A assinatura fica em `out/prototype.jks`. O PIN de quiosque fica no Android. O áudio do assistente é temporário. Os arquivos WAV e JSON da transcrição são apagados ao fim do processamento. A conversa geral e os modelos são locais. Clima e versículo consultam os respectivos provedores. A integração Codex usa o serviço da conta quando habilitada.
