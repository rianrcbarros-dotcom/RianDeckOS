> Registro histórico da versão 5.0. Para a instalação atual, use [Assistente local](ASSISTENTE-LOCAL.md).

# Assistente e inicialização do RianDeckOS 5.0

## Assistente

Abra **Assistente** e toque em **Falar** ou **Digitar**. A captura começa após consentimento e autorização do microfone. **Interromper** encerra a captura e a leitura em voz alta. O histórico guarda até três interações em memória e pode ser limpo na própria aba.

Por padrão, o Android transcreve em português brasileiro e envia apenas texto ao agente pela conexão USB autenticada. O reconhecimento local depende do serviço e das vozes instaladas. No Android 11, a preferência offline não garante processamento local e o serviço pode usar seu próprio provedor. A resposta falada usa a voz do Android. Em **Configurações → Assistente → Preferências de voz**, é possível escolher uma voz pt-BR local ou silenciar a saída.

A opção **Enviar áudio pela USB para transcrição na OpenAI** é voluntária. Nela, a captura WAV dura até 12 segundos, termina após detectar silêncio e usa o supressor de ruído do Android quando disponível. O agente encaminha o WAV em memória à API de transcrição. O texto volta após cada fala. A visualização de nível funciona durante a gravação. A transcrição parcial depende do serviço Android no modo padrão. Nenhum dos modos grava arquivos de áudio. O provedor pode tratar e reter dados segundo as políticas da conta e do serviço.

Configure a variável de ambiente de usuário OPENAI_API_KEY no Windows antes de iniciar o agente para habilitar transcrição via USB e perguntas gerais. A chave permanece no Windows. Envio de perguntas gerais à API exige confirmação. O fluxo usa pedidos por fala, com síntese no Android. A Realtime API suporta streaming bidirecional, mas exigiria uma sessão persistente e uma fila de reprodução e cancelamento adicional. Essa opção não foi implementada nesta versão.

## Comandos e Codex

- Diga **Status do PC** para ler utilização de CPU, RAM e GPU.
- Diga **Abra YouTube**, **Abra Cursor**, **Abra Codex**, **Abra Miro**, **Abra Steam** ou **Abra Spotify** para solicitar um atalho permitido.
- Diga **Próxima música**, **Música anterior** ou **Execute a macro [nome exato]**. Atalhos, mídia e macros pedem confirmação. A variável de ambiente deck-assistant-actions pode ser definida como false no agente Windows para bloquear ações de voz.
- Configure deck-codex-cli com o caminho absoluto do executável Codex e deck-codex-project com a pasta absoluta de um repositório autorizado. O CLI precisa estar autenticado. Em **Configurações → Assistente → Verificar Codex**, o app mostra se os caminhos foram configurados.
- O Codex recebe somente três tarefas de análise predefinidas, com confirmação, timeout, sandbox read-only e aprovação never. Exemplos: **Resuma as alterações recentes do repositório**, **Analise os erros do projeto** e **Verifique os testes que falharam**. A integração não instala dependências, edita arquivos nem configura permissões de escrita.

## Inicialização no Windows

Em **Configurações → Conexão → Inicialização do Windows**, ative ou desative a abertura automática. O agente roda na sessão da conta para acessar mídia e abrir aplicativos interativos. O registro de inicialização fica em HKCU, sem elevação permanente. O agente roda sem console, impede duplicatas na mesma sessão e mantém o túnel ADB associado ao número de série autorizado. Após erro no cabo ou no túnel, tenta restabelecer a comunicação periodicamente. Uma falha fatal do processo requer abertura manual ou novo login. Um serviço Windows em sessão isolada complicaria ações interativas. O Agendador de Tarefas seria uma opção para acrescentar reinício do processo após falha.

Para abrir manualmente, execute out\agent\RianDeck.Agent.exe com --deck-serial=SERIAL. Se a inicialização automática já estiver ativa, preserve a pasta de instalação. Para diagnóstico, execute Diagnose.ps1 e verifique se o ADB mostra o aparelho como device. O APK mantém as políticas de quiosque existentes.
