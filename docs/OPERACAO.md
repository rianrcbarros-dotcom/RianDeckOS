# Operação, testes e direcionamentos

## Rotina de uso

1. Entre no Windows. Ative **Configurações → Conexão → Inicialização do Windows** para o agente iniciar em segundo plano no próximo login. Ao desconectar o cabo, o agente tenta recriar o túnel quando o aparelho volta a ficar disponível.
2. Em **Controle**, toque em um ícone. Mude de página pelas setas ou deslizando a grade. O Git Corporativo Serpro abre o endereço configurado. A navegação depende de a rede resolver o host.
3. Em **Monitoramento**, toque em um card para ver detalhes. Em **Configurações → Monitoramento → Memes**, ajuste limites, animação e GIFs. Os GIFs ficam atrás dos gráficos e os dados vêm do agente.
4. Em **Automações**, abra o Studio no Windows, configure páginas e perfis, salve e exporte o JSON de configuração para backup. O histórico mostra execuções recentes.
5. Em **Assistente**, toque para gravar, fale e toque para enviar. Depois da resposta, toque novamente quando quiser gravar. Exemplos: “Abre o navegador pra mim”, “Pode abrir a calculadora?” e “Status do PC”. Análises pelo Codex pedem confirmação.

## Checklist de aceitação

| Teste | Resultado esperado |
| --- | --- |
| `Diagnose.ps1` e `adb reverse --list` | Aparelho autorizado, túnel 48627 e status autenticado |
| Desconectar e reconectar USB | O agente recria o túnel e o indicador volta a ficar verde |
| Login do Windows | Agente abre em segundo plano. Após reinício, alguns aparelhos precisam ser desbloqueados primeiro |
| Controle | Os atalhos do Windows funcionam e o estado da sessão de mídia reflete o aplicativo de origem |
| Monitoramento | Métricas disponíveis correspondem às fontes do Windows. FPS fica sem medição |
| Assistente | O microfone só capta entre dois toques. Silêncio enviado recebe aviso. Teste a saída no Windows e no alto-falante de mídia do Android |
| `python Test-LocalVoice.py` | Com o agente ligado e a aba Assistente fechada, sintetiza fala de teste e valida saudação, descarte de silêncio e dois turnos pelo WebSocket. A variável `RIANDECK_VOICE_ROOT` permite informar a pasta de voz |
| Quiosque | Use o [roteiro dedicado](../QUIOSQUE.md) apenas em aparelho elegível e verifique a recuperação antes de bloquear |

## Diagnóstico

- **USB desconectado.** Confirme cabo de dados, status `device` e o serial usado. Se o túnel estiver ausente, execute `Connect.ps1` novamente.
- **401 no agente.** APK e agente precisam ter sido compilados com a mesma chave. Recompile e reinstale com a chave local prevista.
- **Assistente indisponível.** Confira os caminhos e nomes dos modelos, `python-path.txt` e `http://127.0.0.1:11434/api/tags`. O processo precisa ter acesso aos arquivos locais. Em caso de captura falha, teste a entrada padrão do Windows e confira a permissão Android.
- **Git Serpro não abre.** Verifique se a rede do notebook resolve o domínio institucional configurado.
- **Voz lenta ou nomes errados.** A primeira carga e a segunda leitura do Whisper podem demorar. Use frases curtas, mantenha voz próxima ao microfone e ajuste velocidade e pausa em **Voz e ritmo**.

## Direcionamentos para manutenção

- Medir separadamente transcrição, resposta e síntese em áudio de fala real, com um conjunto de frases em português e diferentes níveis de ruído.
- Testar a reprodução em diferentes saídas do Windows e a rota de mídia em outros aparelhos Android.
- Validar um segundo ambiente limpo, registrar versões das ferramentas e separar uma configuração de assinatura para distribuição.
- Se houver uma fonte autorizada de FPS, integrar sua métrica real. Preservar o estado sem medição quando a fonte ficar indisponível.
- Revisar procedência e licenças dos GIFs, marcas, fotos e modelos antes de distribuição fora do ambiente privado. Consulte os [créditos](../android/assets/CREDITOS.txt).
