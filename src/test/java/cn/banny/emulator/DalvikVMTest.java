package cn.banny.emulator;

import cn.banny.emulator.linux.Module;
import cn.banny.emulator.linux.ModuleListener;

import java.io.File;
import java.io.IOException;

public class DalvikVMTest implements ModuleListener {

    public static void main(String[] args) throws IOException {
        RunExecutable.run(new File("example_binaries/dalvikvm"), new DalvikVMTest(), "-Xbootclasspath:/system/framework/core.jar:/system/framework/conscrypt.jar:/system/framework/okhttp.jar:/system/framework/core-junit.jar:/system/framework/bouncycastle.jar:/system/framework/ext.jar:/system/framework/framework.jar:/system/framework/framework2.jar:/system/framework/telephony-common.jar:/system/framework/voip-common.jar:/system/framework/mms-common.jar:/system/framework/android.policy.jar:/system/framework/services.jar:/system/framework/apache-xml.jar:/system/framework/webviewchromium.jar:/system/framework/telephony-msim.jar", "-cp", "dex.jar", "DexTest");
    }

    @Override
    public void onLoaded(Emulator emulator, Module module) {
        /*if ("libselinux.so".equals(module.name)) {
            final Debugger debugger = emulator.attach();
            debugger.addBreakPoint(module, 0x06b6d);
        }*/
    }
}
