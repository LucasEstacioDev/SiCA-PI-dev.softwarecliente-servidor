# SiCA-PI-dev.softwarecliente-servidor
Sistema de compartilhamento de arquivos via rede de computadores.

 Cliente do Sistema de Compartilhamento de Arquivos (SiCA).
 *
 * Funcionamento geral:
 * 
 *  - O cliente abre UMA conexão TCP com o servidor (endereço e porta
 *    informados como argumentos ou usando valores padrão) e mantém essa
 *    conexão aberta durante toda a execução.
 *    
 *  - Um menu textual permite ao usuário escolher entre três operações:
      
 *      1) Enviar (upload) um arquivo local para o servidor.
 *      2) Listar os arquivos que estao disponíveis no servidor.
 *      3) Baixar (download) um arquivo do servidor para a máquina local.
 *      4) Sair (encerra a conexão e o programa).

 *   - Nas funçoes "Enviar" e "Baixar" o usuario deve copiar ou digitar o caminho/nome exato do arquivo para que nao haja erros.
     - Caso haja algum erro o usuario pode verificar se nao ha a presença de aspas ("") por conta da copia padrao do windows.
     - Em alguns casos o nome "desktop" teve de ser usado no lugar de "Área de trabalho" no caminho do arquivo, ao enviar.
