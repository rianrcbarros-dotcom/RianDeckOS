# Interface minimalista do Assistente

A aba usa um desenho 2D original, montado em camadas no Canvas nativo Android. O fundo é preto e o painel preserva o cabeçalho comum. A conversa escrita não ocupa espaço na tela principal.

- **Falar** abre a sessão com a entrada padrão do Windows. Durante a escuta, toque novamente para enviar.
- **Interromper** descarta a captura ou para o processamento e a resposta em andamento. Se a sessão de voz estiver ociosa e existir uma tarefa ativa, solicita o cancelamento dessa tarefa.
- **Encerrar** fecha a sessão de voz e desativa a captura. Tarefas já autorizadas podem continuar. Toque no estado que indicar uma tarefa para consultar ou autorizar o plano.

| Arquivo | Alteração |
| --- | --- |
| android/AssistantPersona.java | Desenho vetorial em Canvas com cabeça, olhos, boca e tronco independentes. |
| android/VoiceDeck.java | Interface dividida, três botões, estado real, forma de onda e ligação com a persona. |
| agent/RealtimeDeck.cs | Envelope de volume da entrada e da saída Windows e cancelamento do turno existente. |

O desenho atualiza a até 20 quadros por segundo e para quando a aba está oculta ou o app entra em segundo plano. A boca reage ao envelope RMS do PCM usado na reprodução Windows, com suavização. Ela não resolve fonemas ou visemas e pode apresentar atraso pelo dispositivo de áudio, pelo transporte e pela renderização. A tarefa executada e o estado de voz continuam vindo do agente.

## Atividades do Assistente Pião

Enquanto o Assistente está ocioso, o agendador sorteia um gesto breve a cada 60 segundos e uma atividade a cada 120 segundos. A atividade tem prioridade em caso de coincidência e dura 6,5 segundos. O sorteio percorre café, refrigerante, água, esteira, montanha, louça, bocejo, programação e games antes de iniciar outro ciclo. Durante interações com o agente, o agendador interrompe o que estiver em andamento e recomeça a contagem ao retornar ao repouso.

O avatar usa um desenho articulado em um bitmap reutilizado de 216 × 180 pixels para o personagem e outro de 320 × 120 para a paisagem. O Android amplia essas superfícies com nearest-neighbor. O cenário usa céu, nuvens, montanhas, cidade e reflexos em camadas. Para fixar a iluminação em manhã, tarde ou noite, abra Preferências de voz, Voz e ritmo e Avatar · cenário. Os intervalos ficam no mesmo menu. No modo de economia de energia, a cena reduz a frequência de desenho e pausa o deslocamento do fundo.

A boca usa a amplitude da saída de áudio enviada pelo Windows. A correspondência com fonemas é aproximada. Os movimentos são desenhados em tempo real com o mesmo rosto e a mesma base de camiseta azul.

A cena é interrompida ao escutar, processar, responder ou executar uma tarefa. A animação permanece em Canvas e pausa quando a aba está oculta. A legenda Entendi mostra a última transcrição e é limpa antes de gravar outra.

### Validação do avatar em pixel art

No Moto G20 conectado, uma amostra de 12 segundos na aba Assistente em repouso registrou 184 quadros (aproximadamente 15 por segundo), 6 quadros lentos, mediana de 8 ms e percentil 95 de 10 ms. O PSS do processo inteiro foi de aproximadamente 42 MiB. O teste do agendador com relógio simulado cobriu gestos aos 60 segundos, atividades aos 120 segundos, prioridade quando coincidem, ausência de repetição entre ciclos e retorno ao repouso. A amostra curta não mede a taxa durante as atividades, a memória em uso prolongado nem o desempenho no Moto G7.

### Revisão visual 5.3

Em paisagem, o busto usa até 85% da altura útil e permanece na região esquerda. A região direita ocupa 55% da largura. O fundo é desenhado separadamente e escurece sob os controles e as legendas. Cabeça e roupas usam comandos de desenho pré-gravados. Olhos, óculos, boca, braços e acessórios continuam articulados.

No Moto G20, a amostra de 12 segundos em repouso teve 365 quadros, 17 lentos, mediana de 9 ms e P95 de 11 ms. O PSS do processo inteiro foi 43.064 KiB. Esses números não representam todas as atividades nem o Moto G7.
