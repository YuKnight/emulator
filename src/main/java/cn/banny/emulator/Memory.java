package cn.banny.emulator;

import cn.banny.emulator.linux.IO;
import cn.banny.emulator.linux.Module;
import cn.banny.emulator.linux.ModuleListener;

import java.io.File;
import java.io.IOException;

public interface Memory extends IO {

    long HEAP_BASE = 0x8048000;
    long STACK_BASE = 0xc0000000L;
    int STACK_SIZE_OF_PAGE = 512; // 2M

    long MMAP_BASE = 0x40000000L;

    long getStackPointer();

    long allocateStack(int size);

    void setCallInitFunction();

    void setLibraryResolver(LibraryResolver libraryResolver);

    Module load(File elfFile) throws IOException;
    Module load(File elfFile, boolean forceCallInit) throws IOException;
    byte[] unpack(File elfFile) throws IOException;
    Module findModuleByAddress(long address);

    int mmap(long start, int length, int prot, int flags, int fd, int offset);
    int mprotect(long address, int length, int prot);
    int brk(long address);

    String getMaxLengthSoName();
    long getMaxSizeOfSo();

    int open(String pathname, int oflags);

    int munmap(long start, int length);

    void setModuleListener(ModuleListener listener);
}
