/*
 * Copyright (C) 2015 Willi Ye
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.grarak.kerneladiutor.utils.root;

import android.content.Context;
import android.os.Handler;
import android.util.Log;

import com.grarak.kerneladiutor.utils.Constants;
import com.grarak.kerneladiutor.utils.Utils;
import com.grarak.kerneladiutor.utils.database.SysDB;
import com.grarak.kerneladiutor.utils.kernel.CPU;
import com.grarak.kerneladiutor.utils.kernel.CPUHotplug;

import java.util.List;

/**
 * Created by willi on 14.12.14.
 */
public class Control implements Constants {

    public enum CommandType {
        GENERIC, CPU, TCP_CONGESTION, FAUX_GENERIC
    }

    public static void commandSaver(Context context, String sys, String command) {
        Log.i(TAG, "Run command: " + command);
        SysDB sysDB = new SysDB(context);
        sysDB.create();

        List<SysDB.SysItem> sysList = sysDB.getAllSys();
        for (int i = 0; i < sysList.size(); i++) {
            if (sysList.get(i).getSys().equals(sys))
                sysDB.deleteItem(sysList.get(i).getId());
        }

        sysDB.insertSys(sys, command);
        sysDB.close();
    }

    private static int getChecksum(int arg1, int arg2) {
        return 255 & (Integer.MAX_VALUE ^ (arg1 & 255) + (arg2 & 255));
    }

    private static void runGeneric(String file, String value, Context context) {
        RootUtils.runCommand("echo " + value + " > " + file);

        commandSaver(context, file, "echo " + value + " > " + file);
    }

    private static void runTcpCongestion(String tcpCongestion, Context context) {
        RootUtils.runCommand("sysctl -w net.ipv4.tcp_congestion_control=" + tcpCongestion);

        commandSaver(context, TCP_AVAILABLE_CONGESTIONS, "sysctl -w net.ipv4.tcp_congestion_control=" + tcpCongestion);
    }

    private static void runFauxGeneric(String file, String value, Context context) {
        String command = value.contains(" ") ? value + " " + getChecksum(Utils.stringToInt(value.split(" ")[0]),
                Utils.stringToInt(value.split(" ")[1])) : value + " " + getChecksum(Utils.stringToInt(value), 0);

        RootUtils.runCommand(command);
        // Some kernels don't have checksum
        RootUtils.runCommand("echo " + value + " > " + file);

        commandSaver(context, file, command);
        commandSaver(context, file, "echo " + value + " > " + file);
    }

    public static void startService(String service, boolean save, Context context) {
        RootUtils.runCommand("start " + service);

        if (save) commandSaver(context, service, "start " + service);
    }

    public static void stopService(String service, boolean save, Context context) {
        RootUtils.runCommand("stop " + service);
        if (service.equals(HOTPLUG_MPDEC)) bringCoresOnline();

        if (save) commandSaver(context, service, "stop " + service);
    }

    public static void bringCoresOnline() {
        new Thread() {
            public void run() {
                try {
                    for (int i = 0; i < CPU.getCoreCount(); i++)
                        RootUtils.runCommand("echo 1 > " + String.format(CPU_CORE_ONLINE, i));

                    // Give CPU some time to bring core online
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }
        }.start();
    }

    public static void runCommand(final String value, final String file, final CommandType command, final Context context) {
        Runnable run = new Runnable() {
            @Override
            public void run() {
                if (command == CommandType.CPU) {
                    boolean stoppedMpdec = false;
                    if (CPUHotplug.hasMpdecision() && Utils.isServiceActive(HOTPLUG_MPDEC)) {
                        stopService(HOTPLUG_MPDEC, false, context);
                        stoppedMpdec = true;
                    }

                    if (CPUHotplug.hasMpdecision())
                        for (int i = 0; i < CPU.getCoreCount(); i++)
                            runGeneric(String.format(file, i), value, context);
                    else runGeneric(String.format(file, 0), value, context);

                    if (stoppedMpdec) startService(HOTPLUG_MPDEC, false, context);
                } else if (command == CommandType.GENERIC) {
                    runGeneric(file, value, context);
                } else if (command == CommandType.TCP_CONGESTION) {
                    runTcpCongestion(value, context);
                } else if (command == CommandType.FAUX_GENERIC) {
                    runFauxGeneric(file, value, context);
                }
            }
        };

        new Handler().post(run);

    }

}
