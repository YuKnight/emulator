package cn.banny.emulator.linux;

public interface IO {

    String STDIN = "stdin";
    int FD_STDIN = 0;

    String STDOUT = "stdout";
    int FD_STDOUT = 1;

    String STDERR = "stderr";
    int FD_STDERR = 2;

    int S_IFREG    = 0x8000;   // regular file
    int S_IFCHR    = 0x2000;   // character device

}
