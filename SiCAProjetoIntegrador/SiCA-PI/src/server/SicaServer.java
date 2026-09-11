package server;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

/**
 * SicaServer
 * ==========
 * Servidor do Sistema de Compartilhamento de Arquivos (SiCA).
 *
 * Funcionamento geral:
 *  - O servidor abre um ServerSocket em uma porta fixa e fica em loop
 *    aceitando conexões de clientes.
 *  - Cada cliente que se conecta é atendido em uma THREAD separada
 *    (classe interna ClientHandler), permitindo que múltiplos clientes
 *    usem o SiCA simultaneamente sem bloquear uns aos outros.
 *  - Todos os arquivos enviados pelos clientes são armazenados em uma
 *    pasta local chamada "repositorio" (criada automaticamente se não
 *    existir). Essa pasta funciona como o "disco compartilhado" do SiCA.
 *
 * Protocolo de comunicação (texto simples via BufferedReader/PrintWriter,
 * seguido de bytes brutos quando necessário para o conteúdo do arquivo):
 *
 *   O cliente envia uma linha de comando, que pode ser:
 *
 *   1) UPLOAD <nomeArquivo> <tamanhoEmBytes>
 *        - Servidor responde "OK" e então lê exatamente <tamanhoEmBytes>
 *          bytes do socket, gravando-os em repositorio/<nomeArquivo>.
 *
 *   2) LIST
 *        - Servidor responde com a quantidade de arquivos e, em seguida,
 *          uma linha por arquivo, no formato "<nome> <tamanho>".
 *          Termina com a linha "FIM".
 *
 *   3) DOWNLOAD <nomeArquivo>
 *        - Se o arquivo existir, servidor responde "OK <tamanhoEmBytes>"
 *          e envia os bytes correspondentes.
 *        - Se não existir, servidor responde "ERRO Arquivo nao encontrado".
 *
 *   4) SAIR
 *        - Servidor encerra a conexão com aquele cliente.
 *
 * Cada comando é isolado por uma nova requisição: o cliente pode enviar
 * várias requisições na mesma conexão (o protocolo é "mantém conexão"
 * até o cliente enviar SAIR ou desconectar).
 */
public class SicaServer {

    // Porta TCP onde o servidor escuta as conexões dos clientes.
    private static final int PORTA = 5050;

    // Diretório onde os arquivos compartilhados ficam armazenados no servidor.
    private static final String DIR_REPOSITORIO = "repositorio";

    public static void main(String[] args) {
        // Garante que o diretório do repositório exista antes de iniciar o servidor.
        criarDiretorioRepositorio();

        // Pool de threads para atender múltiplos clientes simultaneamente.
        // Usamos um pool "elástico" (newCachedThreadPool) pois o número de
        // clientes conectados ao SiCA pode variar bastante ao longo do tempo.
        ExecutorService pool = Executors.newCachedThreadPool();

        try (ServerSocket serverSocket = new ServerSocket(PORTA)) {
            System.out.println("=== Servidor SiCA iniciado na porta " + PORTA + " ===");
            System.out.println("Repositorio de arquivos: " + new File(DIR_REPOSITORIO).getAbsolutePath());

            // Loop principal: fica sempre aceitando novas conexões.
            while (true) {
                Socket socketCliente = serverSocket.accept();
                System.out.println("Novo cliente conectado: " + socketCliente.getRemoteSocketAddress());

                // Cada cliente conectado é tratado em uma thread própria,
                // evitando que um cliente lento bloqueie os demais.
                pool.execute(new ClientHandler(socketCliente));
            }
        } catch (IOException e) {
            System.err.println("Erro no servidor: " + e.getMessage());
        } finally {
            pool.shutdown();
        }
    }

    private static void criarDiretorioRepositorio() {
        File dir = new File(DIR_REPOSITORIO);
        if (!dir.exists()) {
            dir.mkdirs();
        }
    }

    /**
     * Classe responsável por atender UM cliente específico, em sua própria
     * thread. Implementa Runnable para poder ser submetida ao pool de threads.
     *
     * Fica em loop lendo comandos do cliente (UPLOAD, LIST, DOWNLOAD, SAIR)
     * até que o cliente decida encerrar a conexão.
     */
    private static class ClientHandler implements Runnable {

        private final Socket socket;

        public ClientHandler(Socket socket) {
            this.socket = socket;
        }

        @Override
        public void run() {
            // try-with-resources garante o fechamento correto dos streams
            // e do socket ao final do atendimento, mesmo em caso de erro.
            try (
                Socket s = this.socket;
                BufferedReader entradaTexto = new BufferedReader(
                        new InputStreamReader(s.getInputStream()));
                PrintWriter saidaTexto = new PrintWriter(s.getOutputStream(), true);
                InputStream entradaBinaria = s.getInputStream();
                OutputStream saidaBinaria = s.getOutputStream()
            ) {
                String linhaComando;

                // Loop: o cliente pode enviar várias
                // requisições na mesma conexão TCP.
                while ((linhaComando = entradaTexto.readLine()) != null) {
                    String[] partes = linhaComando.trim().split("\\s+");
                    if (partes.length == 0 || partes[0].isEmpty()) {
                        continue;
                    }

                    String comando = partes[0].toUpperCase();

                    switch (comando) {
                        case "UPLOAD":
                            tratarUpload(partes, entradaBinaria, saidaTexto);
                            break;

                        case "LIST":
                            tratarList(saidaTexto);
                            break;

                        case "DOWNLOAD":
                            tratarDownload(partes, saidaTexto, saidaBinaria);
                            break;

                        case "SAIR":
                            System.out.println("Cliente " + s.getRemoteSocketAddress() + " encerrou a sessao.");
                            return; // encerra o loop e o try-with-resources fecha tudo

                        default:
                            saidaTexto.println("ERRO Comando desconhecido: " + comando);
                    }
                }
            } catch (IOException e) {
                System.err.println("Erro ao atender cliente: " + e.getMessage());
            }
        }

        private void tratarUpload(String[] partes, InputStream entradaBinaria, PrintWriter saidaTexto) {
            if (partes.length != 3) {
                saidaTexto.println("ERRO Uso: UPLOAD <arquivo> <tamanho>");
                return;
            }

            String nomeArquivo = sanitizarNomeArquivo(partes[1]);
            long tamanho;
            try {
                tamanho = Long.parseLong(partes[2]);
            } catch (NumberFormatException e) {
                saidaTexto.println("ERRO Tamanho invalido");
                return;
            }

            // Sinaliza ao cliente que o servidor está pronto para receber os bytes.
            saidaTexto.println("OK");

            File destino = new File(DIR_REPOSITORIO, nomeArquivo);
            try (OutputStream saidaArquivo = new FileOutputStream(destino)) {
                byte[] buffer = new byte[8192];
                long restante = tamanho;

                // Lê o stream em blocos até completar exatamente "tamanho" bytes.
                while (restante > 0) {
                    int lidos = entradaBinaria.read(buffer, 0,
                            (int) Math.min(buffer.length, restante));
                    if (lidos == -1) {
                        break; // conexão encerrada prematuramente
                    }
                    saidaArquivo.write(buffer, 0, lidos);
                    restante -= lidos;
                }

                saidaTexto.println("UPLOAD_OK");
                System.out.println("Arquivo recebido: " + nomeArquivo + " (" + tamanho + " bytes)");

            } catch (IOException e) {
                saidaTexto.println("ERRO ao salvar arquivo no servidor");
                System.err.println("Erro no upload: " + e.getMessage());
            }
        }

        private void tratarList(PrintWriter saidaTexto) {
            File dir = new File(DIR_REPOSITORIO);
            File[] arquivos = dir.listFiles(File::isFile);

            if (arquivos == null) {
                arquivos = new File[0];
            }

            saidaTexto.println(arquivos.length);
            for (File arquivo : arquivos) {
                saidaTexto.println(arquivo.getName() + " " + arquivo.length());
            }
            saidaTexto.println("FIM");
        }

        private void tratarDownload(String[] partes, PrintWriter saidaTexto, OutputStream saidaBinaria) {
            if (partes.length != 2) {
                saidaTexto.println("ERRO Uso: DOWNLOAD <arquivo>");
                return;
            }

            String nomeArquivo = sanitizarNomeArquivo(partes[1]);
            File arquivo = new File(DIR_REPOSITORIO, nomeArquivo);

            if (!arquivo.exists() || !arquivo.isFile()) {
                saidaTexto.println("ERRO Arquivo nao encontrado");
                return;
            }

            try (InputStream entradaArquivo = new FileInputStream(arquivo)) {
                long tamanho = arquivo.length();
                saidaTexto.println("OK " + tamanho);

                byte[] buffer = new byte[8192];
                int lidos;
                while ((lidos = entradaArquivo.read(buffer)) != -1) {
                    saidaBinaria.write(buffer, 0, lidos);
                }
                saidaBinaria.flush();

                System.out.println("Arquivo enviado ao cliente: " + nomeArquivo + " (" + tamanho + " bytes)");

            } catch (IOException e) {
                System.err.println("Erro no download: " + e.getMessage());
            }
        }

        private String sanitizarNomeArquivo(String nome) {
            return new File(nome).getName();
        }
    }
}
