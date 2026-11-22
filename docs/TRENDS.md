# Trends

Abra **Trends** logo após **Monitoramento**. Toque em uma manchete para abrir o artigo no navegador padrão do Windows. Toque em um indicador para alternar entre os períodos com dados. A variação junto ao preço é da última sessão para os ativos de bolsa e de 24 horas para Bitcoin, mesmo quando o gráfico estiver mostrando outro período.

## Notícias

As notícias são reconsultadas a cada hora enquanto Trends estiver aberta. As miniaturas vêm dos feeds editoriais e passam pelo agente Windows. O Android carrega as imagens em segundo plano e mantém as últimas em memória. Quando o publicador não fornece imagem, o cartão usa a ilustração da categoria.

O agente combina itens da [API pública do Hacker News](https://github.com/HackerNews/API) com os feeds editoriais de [Ars Technica](https://feeds.arstechnica.com/arstechnica/technology-lab) e [The Verge](https://www.theverge.com/rss/index.xml). Consultas temáticas ao Google News RSS ajudam a estimar repercussão por similaridade entre títulos e quantidade de fontes distintas. Só entram no painel notícias de até 48 horas com link HTTPS direto. A classificação combina recência, repercussão e pontuação pública, quando disponível. O horário do Hacker News é o da postagem na comunidade. Nos demais feeds é o horário fornecido pelo publicador. O agente elimina links e manchetes repetidos.

## Indicadores

| Cartão | Métrica | Fonte e cadência enquanto Trends estiver visível |
| --- | --- | --- |
| IA Global | ETF temático [Global X BOTZ](https://globalxetfs.com.br/en/funds/botz/), em USD | Yahoo Finance, 15 minutos |
| Ibovespa | Índice `^BVSP`, em pontos | Yahoo Finance, 5 minutos |
| Nasdaq Composite | Índice `^IXIC`, em pontos | Yahoo Finance, 5 minutos |
| Bitcoin | BTC/USD e variação de 24 horas | [CoinGecko](https://www.coingecko.com/en/api), preço a cada 5 minutos e históricos de 1 h a 4 h |

BOTZ mede o preço de um ETF e usa robótica e IA como tema de investimento. Cada cartão mostra o horário do dado retornado pelo provedor. Os horários de negociação, feriados e eventuais atrasos afetam as cotações. O acesso público a Yahoo Finance e aos feeds está sujeito a mudanças. O agente aceita uma chave Demo opcional pelo parâmetro local `deck-trends-coingecko-key`. Guarde essa configuração fora do repositório e do APK.

Nas bolsas, o período curto mostra a sessão disponível. Os períodos mais longos mostram fechamentos de pregões em 7 dias e no último mês. O Bitcoin usa amostras do CoinGecko de 24 horas, 7 dias e 30 dias.

## Conexão e cache

Os provedores são consultados somente pelo agente Windows. O Android reutiliza a conexão USB e o cliente WebSocket existente, com uma rota autenticada de snapshots. Ao sair de Trends, o aplicativo encerra a assinatura. O cache do Windows fica em `trends-cache` no diretório do agente. O Android conserva o último snapshot para reabrir a tela imediatamente. Se um provedor falhar, o agente mantém os dados válidos e aguarda antes de tentar novamente.

O navegador aceita apenas IDs da seleção atual de notícias. O Windows resolve esses IDs a links HTTPS do próprio feed.

## Validação no Moto G20

Quatro miniaturas editoriais foram carregadas via agente na amostra, com aproximadamente 58 KiB somados. Foram conferidos os quatro indicadores, a troca de períodos, a resposta do comando de abertura de notícia, a recusa de solicitações sem pareamento e a navegação pelas seis abas. O app desenhou dois quadros durante oito segundos de repouso, com cerca de 52 MiB de PSS para o aplicativo inteiro. A amostra mede atividade de desenho e memória, sem estimar autonomia.

Os testes locais também cobriram a lista de origens HTTPS aceitas para miniaturas, a classificação de manchetes, a redução de séries para 64 pontos e o agendador do avatar. A suíte de voz com áudio sintético passou. Os turnos medidos levaram 43,9 e 17,3 segundos, incluindo a reprodução.
