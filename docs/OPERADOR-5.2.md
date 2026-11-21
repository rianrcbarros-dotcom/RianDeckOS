# Operador Windows 5.2

Na aba **Assistente**, toque no botão circular. A sessão usa a entrada e a saída padrão do Windows. Depois da saudação, fale e toque para enviar. Entre perguntas, o microfone fica desativado até o próximo toque.

Pedidos conhecidos geram planos de etapas predefinidas. **Tarefa em andamento** mostra o estado e a contagem real de etapas concluídas. Toque no painel para abrir o plano, autorizar uma análise do Codex ou cancelar. Uma tarefa já autorizada pode seguir enquanto a aba fica fechada.

## Pedidos do operador

| Pedido | Evidência verificável |
| --- | --- |
| Abra o Cursor | Processo do Cursor com janela principal |
| Abra meu projeto | Diretório autorizado existe e janela do Cursor exibe seu nome |
| Peça ao Codex para analisar meu projeto | Autorização no painel, CLI em modo somente leitura, código de saída zero e resultado textual |
| Organize minhas janelas e abra o navegador | Área de trabalho em primeiro plano e janela de navegador reconhecido |
| Como está a tarefa? | Estado do plano registrado no agente |
| Cancele a tarefa | Tarefa marcada como cancelada ou cancelamento solicitado à operação em curso |

A verificação por título da janela confirma o nome exibido pelo Cursor. Ela não lê o diretório interno da sessão do editor. O navegador é verificado pela presença de uma janela de um navegador reconhecido. O operador não inspeciona a página carregada.

## Configuração e dados

No arquivo local appsettings.json do agente, configure deck-codex-cli e deck-codex-project com caminhos absolutos. Autentique-se no Codex CLI com codex login e consulte codex login status. A ação de análise usa um prompt fixo e o sandbox somente leitura. Ao cancelar uma análise em andamento, o agente encerra a árvore do processo.

O histórico local fica em out/agent/studio/assisted-history.json, ignorado pelo Git. Guarda até 25 tarefas, com pedido, etapas, evidências, horários e autorização. Após reiniciar o agente, uma tarefa interrompida aparece como cancelada. A retomada replana e exige confirmação quando nenhuma etapa foi concluída. Se já houve efeitos, solicite individualmente a etapa que falta.

O agente mantém o processamento geral no Ollama local e o modelo Qwen3 1.7B. O operador usa planos fixos e autorizados para executar no Windows. Escrita de código, execução de testes pelo operador, ícones de interrupção de áudio contínua e automação de interface mais ampla não fazem parte desta implementação.

Para validar a abertura e o fluxo de autorização sem iniciar análise, feche uma eventual sessão de voz no celular e execute python Test-AssistedOperator.py com RIANDECK_VOICE_ROOT apontando para a instalação local.
