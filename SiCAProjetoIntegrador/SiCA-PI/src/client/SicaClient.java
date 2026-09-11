package client;

import java.io.*;
import java.net.*;
import java.util.Scanner;

/**
 * SicaClient
 * ==========
 * Cliente do Sistema de Compartilhamento de Arquivos (SiCA).
 *
 * Funcionamento geral:
 *  - O cliente abre UMA conexão TCP com o servidor (endereço e porta
 *    informados como argumentos ou usando valores padrão) e mantém essa
 *    conexão aberta durante toda a execução.
 *  - Um menu textual permite ao usuário escolher entre três operações:
 *      1) Enviar (upload) um arquivo local para o servidor.
 *      2) Listar os arquivos atualmente disponíveis no servidor.
 *      3) Baixar (download) um arquivo do servidor para a máquina local.
 *      4) Sair (encerra a conexão e o programa).
 *
 *  - O protocolo de comunicação com o servidor segue exatamente o que está
 *    documentado em SicaServer.java: comandos de texto (UPLOAD, LIST,
 *    DOWNLOAD, SAIR) seguidos, quando necessário, de dados binários brutos
 *    com tamanho conhecido previamente.
 */
public class SicaClient {

    public static void main(String[] args) {
        // Permite configurar host/porta via linha de comando; caso contrário,
        // assume localhost:5050 como padrão (útil para testes na mesma máquina).
        String host = args.length > 0 ? args[0] : "localhost";
        int porta = args.length > 1 ? Integer.parseInt(args[1]) : 5050;

        try (
            Socket socket = new Socket(host, porta);
            BufferedReader entradaTexto = new BufferedReader(
                    new InputStreamReader(socket.getInputStream()));
            PrintWriter saidaTexto = new PrintWriter(socket.getOutputStream(), true);
            InputStream entradaBinaria = socket.getInputStream();
            OutputStream saidaBinaria = socket.getOutputStream();
            Scanner teclado = new Scanner(System.in)
        ) {
            System.out.println("Conectado ao servidor SiCA em " + host + ":" + porta);

            boolean continuar = true;
            while (continuar) {
                exibirMenu();
                String opcao = teclado.nextLine().trim();

                switch (opcao) {
                    case "1":
                        enviarArquivo(teclado, saidaTexto, entradaTexto, saidaBinaria);
                        break;
                    case "2":
                        listarArquivos(saidaTexto, entradaTexto);
                        break;
                    case "3":
                        baixarArquivo(teclado, saidaTexto, entradaTexto, entradaBinaria);
                        break;
                    case "4":
                        saidaTexto.println("SAIR");
                        continuar = false;
                        System.out.println("Conexao encerrada. Ate logo!");
                        break;
                    default:
                        System.out.println("Opcao invalida. Tente novamente.");
                }
            }

        } catch (UnknownHostException e) {
            System.err.println("Host nao encontrado: " + host);
        } catch (IOException e) {
            System.err.println("Erro de comunicacao com o servidor: " + e.getMessage());
        }
    }

    /** Exibe o menu principal de operações disponíveis ao usuário. */
    private static void exibirMenu() {
        System.out.println("\n===== SiCA - Sistema de Compartilhamento de Arquivos =====");
        System.out.println("1 - Enviar arquivo para o servidor (upload)");
        System.out.println("2 - Listar arquivos disponiveis no servidor");
        System.out.println("3 - Baixar arquivo do servidor (download)");
        System.out.println("4 - Sair");
        System.out.print("Escolha uma opcao: ");
    }

    /**
     * Realiza o UPLOAD de um arquivo local para o servidor.
     *
     * Passos do protocolo (ver documentação em SicaServer.tratarUpload):
     *  1) Pede ao usuário o caminho do arquivo local.
     *  2) Valida se o arquivo existe e é legível.
     *  3) Envia a linha "UPLOAD <nomeArquivo> <tamanho>" ao servidor.
     *  4) Aguarda a confirmação "OK" do servidor.
     *  5) Envia o conteúdo binário do arquivo, byte a byte em blocos.
     *  6) Aguarda a confirmação final "UPLOAD_OK".
     */
    private static void enviarArquivo(Scanner teclado, PrintWriter saidaTexto,
                                       BufferedReader entradaTexto, OutputStream saidaBinaria) {
        System.out.print("Caminho completo do arquivo a enviar: ");
        String caminho = teclado.nextLine().trim();

        File arquivo = new File(caminho);
        if (!arquivo.exists() || !arquivo.isFile()) {
            System.out.println("Arquivo nao encontrado: " + caminho);
            return;
        }

        String nomeArquivo = arquivo.getName();
        long tamanho = arquivo.length();

        try (InputStream entradaArquivo = new FileInputStream(arquivo)) {
            // 1) Envia o comando com nome e tamanho do arquivo.
            saidaTexto.println("UPLOAD " + nomeArquivo + " " + tamanho);

            // 2) Aguarda confirmacao do servidor antes de mandar os bytes.
            String resposta = entradaTexto.readLine();
            if (resposta == null || !resposta.equals("OK")) {
                System.out.println("Servidor nao esta pronto para receber o arquivo: " + resposta);
                return;
            }

            // 3) Envia o conteudo do arquivo em blocos de 8KB.
            byte[] buffer = new byte[8192];
            int lidos;
            while ((lidos = entradaArquivo.read(buffer)) != -1) {
                saidaBinaria.write(buffer, 0, lidos);
            }
            saidaBinaria.flush();

            // 4) Aguarda confirmacao final de que o servidor salvou o arquivo.
            String confirmacao = entradaTexto.readLine();
            if ("UPLOAD_OK".equals(confirmacao)) {
                System.out.println("Arquivo '" + nomeArquivo + "' enviado com sucesso (" + tamanho + " bytes).");
            } else {
                System.out.println("Falha ao enviar arquivo. Resposta do servidor: " + confirmacao);
            }

        } catch (IOException e) {
            System.err.println("Erro ao enviar arquivo: " + e.getMessage());
        }
    }

    /**
     * Solicita ao servidor a listagem de arquivos disponíveis (comando LIST)
     * e exibe o resultado formatado no terminal do cliente.
     *
     * Protocolo esperado (ver SicaServer.tratarList):
     *   - Uma linha com a quantidade de arquivos.
     *   - Uma linha por arquivo, no formato "<nome> <tamanho>".
     *   - Uma linha "FIM" marcando o fim da listagem.
     */
    private static void listarArquivos(PrintWriter saidaTexto, BufferedReader entradaTexto) {
        try {
            saidaTexto.println("LIST");

            String linhaQuantidade = entradaTexto.readLine();
            int quantidade;
            try {
                quantidade = Integer.parseInt(linhaQuantidade.trim());
            } catch (NumberFormatException e) {
                System.out.println("Resposta inesperada do servidor.");
                return;
            }

            System.out.println("\nArquivos disponiveis no servidor (" + quantidade + "):");
            if (quantidade == 0) {
                System.out.println("  (nenhum arquivo disponivel)");
            }

            for (int i = 0; i < quantidade; i++) {
                String linha = entradaTexto.readLine();
                if (linha == null) break;
                String[] partes = linha.split("\\s+");
                if (partes.length == 2) {
                    System.out.printf("  - %-30s %s bytes%n", partes[0], partes[1]);
                }
            }

            // Consome a linha "FIM" que finaliza a listagem.
            entradaTexto.readLine();

        } catch (IOException e) {
            System.err.println("Erro ao listar arquivos: " + e.getMessage());
        }
    }

    /**
     * Realiza o DOWNLOAD de um arquivo do servidor para o disco local.
     *
     * Passos do protocolo (ver SicaServer.tratarDownload):
     *  1) Pede ao usuário o nome do arquivo desejado (deve existir no
     *     servidor; recomenda-se usar a opcao "Listar" antes).
     *  2) Envia "DOWNLOAD <nomeArquivo>" ao servidor.
     *  3) Se a resposta for "OK <tamanho>", lê exatamente essa quantidade
     *     de bytes do socket e grava em um arquivo local (na pasta
     *     "downloads", criada automaticamente).
     *  4) Se a resposta for "ERRO ...", exibe a mensagem de erro.
     */
    private static void baixarArquivo(Scanner teclado, PrintWriter saidaTexto,
                                       BufferedReader entradaTexto, InputStream entradaBinaria) {
        System.out.print("Nome do arquivo a baixar (conforme listagem): ");
        String nomeArquivo = teclado.nextLine().trim();

        try {
            saidaTexto.println("DOWNLOAD " + nomeArquivo);

            String resposta = entradaTexto.readLine();
            if (resposta == null) {
                System.out.println("Sem resposta do servidor.");
                return;
            }

            if (resposta.startsWith("ERRO")) {
                System.out.println(resposta);
                return;
            }

            // Espera formato "OK <tamanho>"
            String[] partes = resposta.split("\\s+");
            if (partes.length != 2 || !partes[0].equals("OK")) {
                System.out.println("Resposta inesperada do servidor: " + resposta);
                return;
            }

            long tamanho = Long.parseLong(partes[1]);

            // Garante a existencia da pasta local de downloads.
            File pastaDownloads = new File("downloads");
            if (!pastaDownloads.exists()) {
                pastaDownloads.mkdirs();
            }

            File destino = new File(pastaDownloads, new File(nomeArquivo).getName());

            try (OutputStream saidaArquivo = new FileOutputStream(destino)) {
                byte[] buffer = new byte[8192];
                long restante = tamanho;

                while (restante > 0) {
                    int lidos = entradaBinaria.read(buffer, 0,
                            (int) Math.min(buffer.length, restante));
                    if (lidos == -1) break;
                    saidaArquivo.write(buffer, 0, lidos);
                    restante -= lidos;
                }
            }

            System.out.println("Arquivo baixado com sucesso em: " + destino.getAbsolutePath()
                    + " (" + tamanho + " bytes)");

        } catch (IOException e) {
            System.err.println("Erro ao baixar arquivo: " + e.getMessage());
        }
    }
}
