package cn.banny.emulator;

import cn.banny.emulator.debugger.Debugger;
import cn.banny.emulator.debugger.SimpleDebugger;
import cn.banny.emulator.dlfcn.Implementation;
import cn.banny.emulator.linux.LinuxSyscallHandler;
import cn.banny.emulator.linux.Module;
import cn.banny.emulator.linux.VirtualMemory;
import cn.banny.emulator.linux.file.FileIO;
import org.apache.commons.io.IOUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.*;

import java.io.File;
import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.util.concurrent.TimeUnit;

import static unicorn.ArmConst.UC_ARM_REG_CPSR;

/**
 * abstract emulator
 * Created by zhkl0228 on 2017/5/2.
 */

public abstract class AbstractEmulator implements Emulator {

    private static final Log log = LogFactory.getLog(AbstractEmulator.class);

    private static final long DEFAULT_TIMEOUT = TimeUnit.HOURS.toMicros(1);

    protected final Unicorn unicorn;

    protected final Memory memory;
    private final int pid;

    protected long timeout = DEFAULT_TIMEOUT;

    private final SyscallHandler syscallHandler;

    public AbstractEmulator(int unicorn_arch, int unicorn_mode, String processName) {
        super();
        this.unicorn = new Unicorn(unicorn_arch, unicorn_mode);
        this.processName = processName == null ? "emulator" : processName;
        switchUserMode();

        unicorn.hook_add(new EventMemHook() {
            @Override
            public boolean hook(Unicorn u, long address, int size, long value, Object user) {
                log.debug("memory failed: address=0x" + Long.toHexString(address) + ", size=" + size + ", value=0x" + Long.toHexString(value) + ", user=" + user);
                return false;
            }
        }, UnicornConst.UC_HOOK_MEM_READ_UNMAPPED | UnicornConst.UC_HOOK_MEM_WRITE_UNMAPPED | UnicornConst.UC_HOOK_MEM_FETCH_UNMAPPED, null);

        this.syscallHandler = new LinuxSyscallHandler(new Implementation(unicorn));
        this.memory = new VirtualMemory(unicorn, this, syscallHandler);

        unicorn.hook_add(syscallHandler, this);

        this.readHook = new TraceMemoryHook();
        this.writeHook = new TraceMemoryHook();
        this.codeHook = new AssemblyCodeDumper(this);

        String name = ManagementFactory.getRuntimeMXBean().getName();
        String pid = name.split("@")[0];
        this.pid = Integer.parseInt(pid);
    }

    private void switchUserMode() {
        int value = ((Number) unicorn.reg_read(UC_ARM_REG_CPSR)).intValue();
        value &= 0x7ffffff0;
        unicorn.reg_write(UC_ARM_REG_CPSR, value);
    }

    private Debugger debugger;

    @Override
    public Debugger attach() {
        if (debugger != null) {
            return debugger;
        }

        debugger = new SimpleDebugger();
        this.unicorn.hook_add(debugger, 1, 0, this);
        this.timeout = 0;
        return debugger;
    }

    @Override
    public int getPid() {
        return pid;
    }

    private boolean traceMemoryRead, traceMemoryWrite;
    private long traceMemoryReadBegin, traceMemoryReadEnd;
    private long traceMemoryWriteBegin, traceMemoryWriteEnd;
    protected boolean traceInstruction;
    private long traceInstructionBegin, traceInstructionEnd;

    @Override
    public final Emulator traceRead(long begin, long end) {
        traceMemoryRead = true;
        traceMemoryReadBegin = begin;
        traceMemoryReadEnd = end;
        return this;
    }

    @Override
    public final Emulator traceWrite(long begin, long end) {
        traceMemoryWrite = true;
        traceMemoryWriteBegin = begin;
        traceMemoryWriteEnd = end;
        return this;
    }

    @Override
    public final Emulator traceRead() {
        return traceRead(1, 0);
    }

    @Override
    public final Emulator traceWrite() {
        return traceWrite(1, 0);
    }

    @Override
    public final void traceCode() {
        traceCode(1, 0);
    }

    @Override
    public final void traceCode(long begin, long end) {
        traceInstruction = true;
        traceInstructionBegin = begin;
        traceInstructionEnd = end;
    }

    private final ReadHook readHook;
    private final WriteHook writeHook;
    private final AssemblyCodeDumper codeHook;

    /**
     * Emulate machine code in a specific duration of time.
     * @param begin    Address where emulation starts
     * @param until    Address where emulation stops (i.e when this address is hit)
     * @param timeout  Duration to emulate the code (in microseconds). When this value is 0, we will emulate the code in infinite time, until the code is finished.
     * @param alts     The function replacements
     */
    protected final Number emulate(long begin, long until, long timeout, boolean entry, Alt... alts) {
        try {
            long context = unicorn.context_alloc();
            unicorn.context_save(context);

            if (entry) {
                if (traceMemoryRead) {
                    traceMemoryRead = false;
                    unicorn.hook_add(readHook, traceMemoryReadBegin, traceMemoryReadEnd, null);
                }
                if (traceMemoryWrite) {
                    traceMemoryWrite = false;
                    unicorn.hook_add(writeHook, traceMemoryWriteBegin, traceMemoryWriteEnd, null);
                }
                if (traceInstruction) {
                    traceInstruction = false;
                    codeHook.initialize(traceInstructionBegin, traceInstructionEnd, alts);
                    unicorn.hook_add(codeHook, traceInstructionBegin, traceInstructionEnd, null);
                }
            }

            unicorn.emu_start(begin, until, timeout, (long) 0);

            Number r0 = (Number) unicorn.reg_read(ArmConst.UC_ARM_REG_R0);
            unicorn.context_restore(context);
            unicorn.free(context);
            return r0;
        } catch (RuntimeException e) {
            e.printStackTrace();
            attach().debug(this);
            IOUtils.closeQuietly(this);
            throw e;
        } finally {
            if (entry) {
                unicorn.hook_del(readHook);
                unicorn.hook_del(writeHook);
                unicorn.hook_del(codeHook);
            }
        }
    }

    private boolean closed;

    @Override
    public synchronized final void close() throws IOException {
        if (closed) {
            throw new IOException("Already closed.");
        }

        try {
            for (FileIO io : syscallHandler.fdMap.values()) {
                io.close();
            }

            closeInternal();

            unicorn.close();
        } finally {
            closed = true;
        }
    }

    protected abstract void closeInternal();

    @Override
    public Module loadLibrary(File libraryFile) throws IOException {
        return memory.load(libraryFile);
    }

    @Override
    public Module loadLibrary(File libraryFile, boolean forceCallInit) throws IOException {
        return memory.load(libraryFile, forceCallInit);
    }

    @Override
    public Alignment align(long addr, long size) {
        long to = getPageAlign();
        long mask = -to;
        long right = addr + size;
        right = (right + to - 1) & mask;
        addr &= mask;
        size = right - addr;
        size = (size + to - 1) & mask;
        return new Alignment(addr, size);
    }

    @Override
    public Memory getMemory() {
        return memory;
    }

    @Override
    public Unicorn getUnicorn() {
        return unicorn;
    }

    private final String processName;

    @Override
    public String getProcessName() {
        return processName == null ? "emulator" : processName;
    }
}
