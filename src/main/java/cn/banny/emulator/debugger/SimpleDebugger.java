package cn.banny.emulator.debugger;

import capstone.Capstone;
import cn.banny.auxiliary.Inspector;
import cn.banny.emulator.Emulator;
import cn.banny.emulator.arm.ARM;
import cn.banny.emulator.linux.Module;
import cn.banny.emulator.pointer.UnicornPointer;
import com.sun.jna.Pointer;
import net.fornwall.jelf.ElfSymbol;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import unicorn.ArmConst;
import unicorn.Unicorn;
import unicorn.UnicornException;

import java.io.IOException;
import java.util.*;

public class SimpleDebugger implements Debugger {

    private static final Log log = LogFactory.getLog(SimpleDebugger.class);

    private final Map<Long, Module> breakMap = new HashMap<>();

    @Override
    public void addBreakPoint(Module module, String symbol) {
        try {
            ElfSymbol elfSymbol = module.getELFSymbolByName(symbol);
            if (elfSymbol == null) {
                throw new IllegalStateException("find symbol failed: " + symbol);
            }
            addBreakPoint(module, elfSymbol.value);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void addBreakPoint(Module module, long offset) {
        long address = (module.base + offset) & 0xfffffffeL;
        if (log.isDebugEnabled()) {
            log.debug("addBreakPoint address=0x" + Long.toHexString(address));
        }
        breakMap.put(address, module);
    }

    private class CodeHistory {
        final long address;
        final String asm;
        CodeHistory(long address, String asm) {
            this.address = address;
            this.asm = asm;
        }
    }

    private final List<CodeHistory> historyList = new ArrayList<>();

    @Override
    public void hook(Unicorn u, long address, int size, Object user) {
        Emulator emulator = (Emulator) user;

        while (historyList.size() > 10) {
            historyList.remove(0);
        }
        Capstone.CsInsn[] insns = emulator.disassemble(address, size, 0);
        historyList.add(new CodeHistory(address, ARM.assembleDetail(emulator.getMemory(), insns[0], address, ARM.isThumb(u))));

        if (singleStep) {
            loop(emulator, u, address, size);
            return;
        }

        if (breakMap.containsKey(address)) {
            Module breakModule = breakMap.get(address);
            if (breakModule == null) {
                breakMap.remove(address); // remove temp breakpoint
            }
            loop(emulator, u, address, size);
        }
    }

    @Override
    public void debug(Emulator emulator) {
        Unicorn unicorn = emulator.getUnicorn();
        long address = ((Number) unicorn.reg_read(ArmConst.UC_ARM_REG_PC)).intValue() & 0xffffffffL;
        loop(emulator, unicorn, address, 0);
    }

    private boolean singleStep;

    private void loop(Emulator emulator, Unicorn u, long address, int size) {
        System.out.println("debugger break at: 0x" + Long.toHexString(address));
        singleStep = false;
        boolean thumb = ARM.isThumb(u);
        long nextAddress = 0;
        try {
            emulator.showRegs();
            nextAddress = disassemble(emulator, address, size, thumb);
        } catch (UnicornException e) {
            e.printStackTrace();
        }

        Scanner scanner = new Scanner(System.in);
        String line;
        while ((line = scanner.nextLine()) != null) {
            try {
                if ("d".equals(line) || "dis".equals(line)) {
                    disassemble(emulator, address, size, thumb);
                    continue;
                }
                if (line.startsWith("m")) {
                    String command = line;
                    String[] tokens = line.split("\\s+");
                    int length = 0x70;
                    try {
                        if (tokens.length >= 2) {
                            command = tokens[0];
                            int radix = 10;
                            String str = tokens[1];
                            if (str.startsWith("0x")) {
                                str = str.substring(2);
                                radix = 16;
                            }
                            length = Integer.parseInt(str, radix);
                        }
                    } catch(NumberFormatException ignored) {}

                    int reg = -1;
                    String name = null;
                    if (command.startsWith("mr") && command.length() == 3) {
                        char c = command.charAt(2);
                        if (c >= '0' && c <= '7') {
                            int r = c - '0';
                            reg = ArmConst.UC_ARM_REG_R0 + r;
                            name = "r" + r;
                        }
                    } else if ("mfp".equals(command)) {
                        reg = ArmConst.UC_ARM_REG_FP;
                        name = "fp";
                    } else if ("mip".equals(command)) {
                        reg = ArmConst.UC_ARM_REG_IP;
                        name = "ip";
                    } else if ("msp".equals(command)) {
                        reg = ArmConst.UC_ARM_REG_SP;
                        name = "sp";
                    } else if (command.startsWith("m0x")) {
                        long addr = Long.parseLong(command.substring(3), 16);
                        Pointer pointer = UnicornPointer.pointer(u, addr);
                        if (pointer != null) {
                            Inspector.inspect(pointer.getByteArray(0, length), pointer.toString());
                        } else {
                            System.out.println(addr + " is null");
                        }
                        continue;
                    }
                    if (reg != -1) {
                        Pointer pointer = UnicornPointer.register(u, reg);
                        if (pointer != null) {
                            Inspector.inspect(pointer.getByteArray(0, length), name + "=" + pointer);
                        } else {
                            System.out.println(name + " is null");
                        }
                        continue;
                    }
                }
                if (line.startsWith("b0x")) {
                    long addr = Long.parseLong(line.substring(3), 16) & 0xFFFFFFFFFFFFFFFEL;
                    breakMap.put(addr, null); // temp breakpoint
                    System.out.println("Add temporarily breakpoint: 0x" + Long.toHexString(addr));
                    continue;
                }
                if ("blr".equals(line)) { // break LR
                    breakMap.put(((Number) u.reg_read(ArmConst.UC_ARM_REG_LR)).intValue() & 0xffffffffL, null);
                    continue;
                }
                if ("c".equals(line)) { // continue
                    break;
                }
                if ("n".equals(line)) {
                    if (nextAddress == 0) {
                        System.out.println("Next address failed.");
                        continue;
                    } else {
                        // System.out.println("Add temporarily breakpoint: 0x" + Long.toHexString(nextAddress));
                        breakMap.put(nextAddress, null);
                        break;
                    }
                }
                if ("s".equals(line) || "si".equals(line)) {
                    singleStep = true;
                    break;
                }
                if ("stop".equals(line)) {
                    u.emu_stop();
                    break;
                }
            } catch (UnicornException e) {
                e.printStackTrace();
            }
        }
    }

    /**
     * @return next address
     */
    private long disassemble(Emulator emulator, long address, int size, boolean thumb) {
        long next = 0;
        boolean on = false;
        StringBuilder sb = new StringBuilder();
        for (CodeHistory history : historyList) {
            if (history.address == address) {
                sb.append("=>  ");
                on = true;
            } else {
                sb.append("    ");
                if (on) {
                    next = history.address;
                    on = false;
                }
            }
            sb.append(history.asm).append('\n');
        }
        long nextAddr = address + size;
        Capstone.CsInsn[] insns = emulator.disassemble(nextAddr, 4 * 10, 10);
        for (Capstone.CsInsn ins : insns) {
            if (nextAddr == address) {
                sb.append("=>  ");
                on = true;
            } else {
                sb.append("    ");
                if (on) {
                    next = nextAddr;
                    on = false;
                }
            }
            sb.append(ARM.assembleDetail(emulator.getMemory(), ins, nextAddr, thumb)).append('\n');
            nextAddr += ins.size;
        }
        System.out.println(sb);
        return next;
    }

}
