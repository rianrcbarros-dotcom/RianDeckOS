# RianDeckOS 3.1 em modo quiosque

O aplicativo é Android nativo em Java. Ele mantém a interface atual, o agente Windows e o túnel USB autenticado. Em quiosque, o app deve ser Device Owner e o Android deve confirmar `LOCK_TASK_MODE_LOCKED`. A aparência em tela cheia, sozinha, não é critério de aceite.

## Preflight do aparelho conectado

O aparelho inspecionado é o Moto G20 XT2128-1, Android 11 (API 30), usuário principal único, recurso `android.software.device_admin`, configuração inicial concluída e uma conta no Android. A versão do comando DPM nesse aparelho não oferece `list-owners`; use `dumpsys device_policy`. Não foi encontrada indicação de Device Owner ou Profile Owner na verificação. O provisionamento não foi tentado. O APK 3.1 pode ser usado no modo normal nesse estado.

## Provisionamento em um Android 11 elegível

1. Confirme o modelo e o serial, o usuário principal e a ausência de outro proprietário, perfis incompatíveis ou contas que bloqueiem o provisionamento. Use `adb devices -l`, `adb shell pm list users`, `adb shell dumpsys device_policy` e a contagem em `adb shell dumpsys account`. Não compartilhe os nomes das contas.
2. Preserve dados e credenciais. Se for necessário remover uma conta ou restaurar o aparelho para torná-lo elegível, combine isso antes. Uma restauração de fábrica apaga dados locais. Não execute restauração, root, desbloqueio de bootloader ou flash para seguir este roteiro sem autorização.
3. Instale o APK 3.1 assinado com a chave atual. No app, em **Configurações → Modo dedicado**, configure e confirme seu PIN de 6 a 10 dígitos. O PIN é local. A chave HMAC fica no Android Keystore e o app guarda um verificador com PBKDF2 e salt. Cinco erros suspendem novas tentativas por 60 segundos.
4. Em um dispositivo elegível, com o serial conferido e USB autorizado, faça o provisionamento pelo ADB. Este é o roteiro de teste do Android Enterprise, não um fluxo QR de implantação em frota.

   ```powershell
   $adb = 'C:\RianDeck\platform-tools\adb.exe'
   & $adb -s SERIAL_DO_APARELHO shell dpm set-device-owner --user 0 br.com.rian.deck/.DeckAdminReceiver
   & $adb -s SERIAL_DO_APARELHO shell dumpsys device_policy
   ```

5. Se o Android recusar, pare e leia a causa. Não remova contas nem tente contornar a recusa. Com o proprietário confirmado, abra o app e toque em **Configurações → Modo dedicado → Ativar quiosque**. Confira **Device Owner: sim** e **Quiosque: ativo**.

O modo ativo registra o RianDeckOS como Home persistente, autoriza apenas seu pacote para Lock Task, desabilita os recursos configuráveis da interface do sistema e solicita bloqueio da barra de status. A referência bíblica abre dentro do app durante o quiosque. O launcher persistente, o receptor de boot e o agente ADB são mecanismos complementares. A criptografia e o bloqueio de tela do aparelho podem exigir o primeiro desbloqueio do Android após reiniciar.

## Liberação e recuperação

Em **Configurações → Sistema → Desbloquear tela cheia**, informe o PIN. Ao confirmar, o app encerra Lock Task, desfaz o launcher persistente, retira seu pacote da lista de Lock Task e restaura as barras do Android. O registro de Device Owner permanece para permitir reativar o modo.

Se o app falhar, observe se o launcher persistente o reabre e se o agente restabelece o túnel. Se o ADB continuar previamente autorizado, instale novamente um APK corrigido assinado com a mesma chave usando `adb install -r`. Isso preserva os dados e o gerenciamento. Se o PIN for perdido ou os dados de segurança ficarem inacessíveis, procure o administrador responsável. O APK de produção não oferece PIN mestre, desbloqueio remoto ou remoção de Device Owner via ADB. Uma recuperação que dependa de restauração requer backup e autorização.

## Aceite em dispositivo provisionado

Antes de liberar o aparelho para uso dedicado, registre Home, Recentes, gestos laterais, cortina de notificações, configurações rápidas, tentativa de abrir outro app e funcionamento dos atalhos do Windows. Valide cinco PINs incorretos, o bloqueio temporário, o PIN correto e a volta das barras. Por fim, teste reinicialização do Android e do Windows, desconexão física do USB e recuperação após falha do app. Registre quando a tela voltar ao Deck, com ou sem o primeiro desbloqueio. Não marque esses testes como aprovados no Moto G20 enquanto ele não estiver provisionado.
