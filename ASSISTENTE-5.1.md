> Registro histórico do modo Realtime. Para a instalação atual, use [Assistente local](ASSISTENTE-LOCAL.md).

# RianDeckOS 5.1 · Conversa por voz

## Começar

Na aba **Assistente**, autorize o envio de áudio e o microfone. Depois da primeira autorização, entrar na aba inicia uma sessão; sair dela encerra a captura e a conexão. **Encerrar conversa** finaliza a sessão e **Voltar à conversa** inicia outra. O áudio é reproduzido no celular. As legendas são opcionais.

Em **Configurações → Assistente → Preferências de voz**, selecione voz, ritmo, volume, estilo, saudação e tempo de inatividade. A saudação usa a hora local do celular. Por padrão, o agente pula a saudação quando detecta mídia tocando na sessão Windows. A agenda não está integrada. Trocar de aba fecha a sessão Realtime e descarta seu contexto local; ao voltar, a saudação é curta.

Se o Android negar a permissão ou a captura falhar, o agente usa a entrada padrão de gravação do Windows selecionada em **Configurações do Windows → Sistema → Som → Entrada**. É possível ativar **Priorizar microfone padrão do Windows** ou executar **Testar entrada do Windows** no Deck. O teste mede apenas se o Windows entregou amostras e não as envia à internet. O microfone escolhido é aberto para cada sessão. Um microfone fisicamente mudo que ainda entrega amostras silenciosas pode precisar de seleção manual da alternativa.

## Credenciais e custos

O agente precisa da variável de ambiente OPENAI_API_KEY no Windows, com faturamento da API ativo e acesso ao modelo. Defina a variável no ambiente do usuário e reinicie o agente para ele herdá-la. A chave fica no Windows e nunca é enviada ao APK. Não cole a chave na conversa nem passe pela linha de comando.

O modelo padrão é gpt-realtime-2.1-mini. O agente pode selecionar outro modelo compatível pela variável deck-realtime-model. A sessão transmite PCM por WebSocket autenticado no túnel ADB e o agente conecta à OpenAI por WSS. Quando ativa, envia os trechos necessários à detecção de fala, inclusive intervalos de silêncio. O Deck mantém áudio em memória e não grava arquivos. A OpenAI trata os dados segundo os controles e as políticas da conta da API.

A assinatura ChatGPT e a API usam faturamentos separados. Consulte [preços da API](https://developers.openai.com/api/docs/pricing), [faturamento do ChatGPT e API](https://help.openai.com/en/articles/9039756-managing-billing-for-chatgpt-and-the-api-platform) e [documentação do Realtime](https://developers.openai.com/api/docs/guides/realtime-conversations).

## Codex

Use codex login status no Windows para conferir a sessão oficial. Para análise de projeto, defina deck-codex-cli e deck-codex-project no ambiente do agente. A pasta autorizada deve ser um repositório válido. O assistente pode ler estado do PC, propor abertura do Cursor ou do Codex, e solicitar resumo de mudanças, análise de erros ou leitura de relatórios de testes. A ação é confirmada na tela do celular. As análises usam comandos predefinidos no CLI com sandbox read-only e expiram em 90 segundos. A ferramenta não executa testes arbitrários, instala dependências ou edita arquivos. Encerrar a sessão cancela trabalho do CLI ainda em andamento.

A inicialização configurada no Windows continua ocorrendo no login, com reconexão USB. O microfone não é iniciado no boot.

## Limites de validação

A captura, interrupção por fala e qualidade da voz ponta a ponta precisam ser verificadas com uma chave e faturamento da API ativos. A eficácia contra eco depende do aparelho e do caminho de áudio. A saída é no celular. Não há sincronização de agenda, seletor de múltiplos projetos ou painel de execução de testes nesta versão.
