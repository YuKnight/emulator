package cn.banny.emulator.linux.file;

import cn.banny.emulator.pointer.UnicornPointer;
import com.sun.jna.Pointer;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.Unicorn;

class Ashmem extends DriverFileIO {

    private static final Log log = LogFactory.getLog(Ashmem.class);

    Ashmem(int oflags, String path) {
        super(oflags, path);
    }

    private static final int ASHMEM_SET_NAME = 0x41007701;
    private static final int ASHMEM_SET_SIZE = 0x40047703;

    private String name;
    private int size;

    @Override
    public int ioctl(Unicorn unicorn, long request, long argp) {
        if (request == ASHMEM_SET_NAME) {
            Pointer pointer = UnicornPointer.pointer(unicorn, argp);
            assert pointer != null;
            this.name = pointer.getString(0);
            log.debug("ashmem set name: " + this.name);
            return 0;
        }
        if (request == ASHMEM_SET_SIZE) {
            this.size = (int) argp;
            log.debug("ashmem set size: " + this.size);
            return 0;
        }

        return super.ioctl(unicorn, request, argp);
    }

    @Override
    byte[] getMmapData() {
        return new byte[0];
    }
}
