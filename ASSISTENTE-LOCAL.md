# Assistente local do RianDeckOS 5.1

Abra **Assistente**. Depois do consentimento inicial, a sessão começa automaticamente. O botão circular inicia a gravação. Toque de novo para enviar. Depois da resposta, toque para gravar a próxima pergunta. Sair da aba encerra a sessão. A voz masculina Edresson sai pelo dispositivo padrão do notebook. Em Voz e ritmo → Saída de áudio, é possível selecionar o alto-falante de mídia do celular. O aplicativo encerra a escuta ao sair da aba. Em **Configurações → Assistente → Preferências de voz**, ajuste a voz brasileira, a velocidade, as pausas, o volume, as legendas, a saudação e o tempo de repouso. Para usar a entrada selecionada no Windows, ative **Priorizar microfone padrão do Windows**. O agente também usa essa entrada se a permissão ou a captura no celular falhar.

O agente usa a hora local do Android nas saudações informais. A pronúncia enviada ao sintetizador é “Rrian”. A voz começa em 118% da velocidade e com 100 ms de pausa entre frases. O texto na tela mantém “Rian”. A opção **Respeitar mídia tocando** pula a saudação quando há mídia ativa no Windows.

## Processamento e privacidade

O áudio passa pelo túnel USB já autenticado. O Whisper.cpp transcreve em português, o Ollama executa Qwen3 1.7B na própria máquina e o Piper converte a resposta em fala. O agente só transcreve após o segundo toque. Descarta captações vazias e recorta silêncio nas pontas, preservando pausas no meio. Cada pergunta pode ter até 20 segundos. O cancelamento de eco depende do Android e da entrada escolhida no Windows. Nenhum componente deste fluxo exige chave de API ou mensalidade.

O agente mantém apenas os últimos turnos na memória até fechar a sessão. O Whisper recebe um WAV temporário que o agente apaga após o uso. O Ollama escuta em 127.0.0.1. O modelo é pré-carregado ao abrir o Assistente e liberado após três minutos sem uso. O primeiro carregamento é mais lento. O agente inicia no login do Windows e recupera o túnel ADB. O Ollama portátil abre em segundo plano sob demanda. O microfone fica desativado até a aba Assistente ser aberta com consentimento.

Componentes locais ficam em D:\Baixados\RianDeckVoice. A configuração deck-local-voice-root permite outro diretório. O Whisper usa models\ggml-base.bin e repete a transcrição com models\ggml-small-q5_1.bin quando a confiança média é baixa, o Piper usa as vozes pt_BR-faber-medium e pt_BR-edresson-low e o Ollama utiliza o diretório ollama-models. Para testar, execute python Test-LocalVoice.py. Ele sintetiza perguntas de teste e as envia pela mesma rota de áudio da interface.

## Codex

A autenticação oficial é feita com codex login e a opção de entrar com a conta ChatGPT no navegador. Use codex login status para verificar a sessão. O acesso CLI é oferecido no plano Free com limites de uso. As perguntas gerais nunca usam essa sessão. Somente comandos de programação predefinidos podem ser encaminhados ao Codex, com confirmação no celular e análise somente leitura. Para análise de um repositório, ainda é preciso indicar o caminho absoluto do Codex e do projeto autorizado em deck-codex-cli e deck-codex-project.

Fontes: [Codex e planos ChatGPT](https://help.openai.com/en/articles/11369540-using-codex-with-your-chatgpt-plan), [Qwen3 1.7B](https://ollama.com/library/qwen3%3A1.7b), [Whisper.cpp](https://github.com/ggml-org/whisper.cpp), [Piper](https://github.com/OHF-Voice/piper1-gpl).
