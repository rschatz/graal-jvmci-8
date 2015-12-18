/*
 * Copyright (c) 2009, 2011, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 *
 */

/**
 * The main command line driver of a parser for LogCompilation output.
 * @author never
 */

package com.sun.hotspot.tools.compiler;

import java.io.PrintStream;
import java.util.*;
import org.xml.sax.*;
import org.xml.sax.helpers.*;

public class LogCompilation extends DefaultHandler implements ErrorHandler, Constants {

    public static void usage(int exitcode) {
        System.out.println("Usage: LogCompilation [ -v ] [ -c ] [ -s ] [ -e | -n ] file1 ...");
        System.out.println("  -c:   clean up malformed 1.5 xml");
        System.out.println("  -i:   print inlining decisions");
        System.out.println("  -S:   print compilation statistics");
        System.out.println("  -R:   print method recompilation information");
        System.out.println("  -s:   sort events by start time");
        System.out.println("  -e:   sort events by elapsed time");
        System.out.println("  -n:   sort events by name and start");
        System.out.println("  -L:   print eliminated locks");
        System.out.println("  -Q:   print compile queue activity");
        System.exit(exitcode);
    }

    public static void main(String[] args) throws Exception {
        Comparator<LogEvent> defaultSort = LogParser.sortByStart;
        boolean statistics = false;
        boolean recompilation = false;
        boolean printInlining = false;
        boolean cleanup = false;
        boolean printEliminatedLocks = false;
        boolean printCompileQueue = false;
        int index = 0;

        while (args.length > index) {
            if (args[index].equals("-e")) {
                defaultSort = LogParser.sortByElapsed;
                index++;
            } else if (args[index].equals("-n")) {
                defaultSort = LogParser.sortByNameAndStart;
                index++;
            } else if (args[index].equals("-s")) {
                defaultSort = LogParser.sortByStart;
                index++;
            } else if (args[index].equals("-c")) {
                cleanup = true;
                index++;
            } else if (args[index].equals("-S")) {
                statistics = true;
                index++;
            } else if (args[index].equals("-R")) {
                recompilation = true;
                index++;
            } else if (args[index].equals("-h")) {
                usage(0);
            } else if (args[index].equals("-i")) {
                printInlining = true;
                index++;
            } else if (args[index].equals("-L")) {
                printEliminatedLocks = true;
                index++;
            } else if (args[index].equals("-Q")) {
                printCompileQueue = true;
                index++;
            } else {
                break;
            }
        }

        if (index >= args.length) {
            usage(1);
        }

        while (index < args.length) {
            ArrayList<LogEvent> events = LogParser.parse(args[index], cleanup);

            if (printCompileQueue) {
                printCompileQueue(events, System.out);
            } else if (printEliminatedLocks) {
                printEliminatedLocks(events, System.out, defaultSort);
            } else if (statistics) {
                printStatistics(events, System.out);
            } else if (recompilation) {
                printRecompilation(events, System.out);
            } else {
                Collections.sort(events, defaultSort);
                for (LogEvent c : events) {
                    if (c instanceof NMethod) continue;
                    if (c instanceof TaskEvent) continue;

                    System.out.printf("%f ", c.getStart());
                    if (printInlining && c instanceof Compilation) {
                        Compilation comp = (Compilation)c;
                        comp.print(System.out, true);
                    } else {
                        c.print(System.out);
                    }
                }
            }
            index++;
        }
    }
    
    public static void printCompileQueue(ArrayList<LogEvent> events, PrintStream out) {
        int[] levels = new int[5];
        HashMap<String, TaskEvent> tasks = new HashMap<String, TaskEvent>();
        
        out.printf("%7s ", "Stamp");
        for (int i = 1; i <= 4; i++) {
            out.printf(" Level%d", i);
        }
        out.printf("   %10s", "Kind");
        out.println();
        for (LogEvent e : events) {
            if (e instanceof TaskEvent) {
                boolean demoted = false;
                TaskEvent t = (TaskEvent) e;
                TaskEvent start = tasks.get(t.getId());
                switch (t.getKind()) {
                case Enqueue:
                    assert start == null;
                    levels[t.getLevel()] = levels[t.getLevel()] + 1;
                    tasks.put(t.getId(), t);
                    break;
                    
                case Finish:
                case Dequeue:
                    if (start != null && start.getLevel() != t.getLevel()) {
                        // Sometimes tasks are moved to a lower
                        // compilation level when the queue fills.
                        demoted = true;
                        levels[start.getLevel()] = levels[start.getLevel()] - 1;
                    } else {
                        levels[t.getLevel()] = levels[t.getLevel()] - 1;
                    }
                    break;
                default:
                    throw new InternalError();
                }
                out.printf("%7.3f ", t.getStart());
                for (int i = 1; i <= 4; i++) {
                    out.printf(" %6d", levels[i]);
                }
                out.printf("   %10s", t.getKind());
                if (t.getComment() != null) {
                    out.printf(" %s", t.getComment());
                }
                if (demoted) {
                    out.printf("  %d->%d", start.getLevel(), t.getLevel());
                }
                out.println();
            }
        }
    }

    public static void printEliminatedLocks(ArrayList<LogEvent> events, PrintStream out, Comparator<LogEvent> defaultSort) {
        Collections.sort(events, defaultSort);
        for (LogEvent e : events) {
            if (e instanceof Compilation) {
                Compilation c = (Compilation) e;
                List<JVMState> eliminated = c.getEliminatedLocks();
                if (!eliminated.isEmpty()) {
                    c.print(out);
                    out.println("  Eliminated locks");
                    for (JVMState jvms : eliminated) {
                        System.err.print("   ");
                        while (jvms != null) {
                            out.printf(" %s.%s@%d", jvms.method.getHolder().replace('/', '.'), jvms.method.getName(), jvms.bci);
                            jvms = jvms.outer;
                        }
                        out.println();
                    }
                }
            }
        }
    }
    
    public static void printRecompilation(ArrayList<LogEvent> events, PrintStream out) {
        LinkedHashMap<String, Map<String, List<String>>> traps = new LinkedHashMap<String, Map<String, List<String>>>();
        for (LogEvent e : events) {
            if (e instanceof UncommonTrapEvent) {
                UncommonTrapEvent uc = (UncommonTrapEvent) e;
                Map<String, List<String>> t = traps.get(uc.getCompilation().getMethod().toString());
                if (t == null) {
                    t = new LinkedHashMap<String, List<String>>();
                    traps.put(uc.getCompilation().getMethod().toString(), t);
                }
                String msg = uc.formatTrap().trim();
                List<String> i = t.get(msg);
                if (i == null) {
                    i = new ArrayList<String>();
                    t.put(msg, i);
                }
                i.add(uc.getId());
            }
        }

        List<List<String>> recompiles = new ArrayList<List<String>>();
        Map<List<String>, String> reverseMapping = new HashMap<List<String>, String>();
        for (Map.Entry<String, Map<String, List<String>>> entry : traps.entrySet()) {
            for (Map.Entry<String, List<String>> trapEntry : entry.getValue().entrySet()) {
                recompiles.add(trapEntry.getValue());
                reverseMapping.put(trapEntry.getValue(), trapEntry.getKey());
            }
        }
        recompiles.sort(new Comparator<List<String>>() {
            public int compare(List<String> a, List<String> b) {
                return intCompare(a.size(), b.size());
            }
        });
        for (List<String> key : recompiles) {
	    out.print("Trap: ");
	    out.println(reverseMapping.get(key));
	    out.print("Compilations: ");
	    out.println(key);
        }
    }
    

    private static int intCompare(int o1, int o2) {
        return (o1 > o2 ? -1 : (o1 == o2 ? 0 : 1));
    }

    public static void printStatistics(ArrayList<LogEvent> events, PrintStream out) {
        long cacheSize = 0;
        long maxCacheSize = 0;
        int nmethodsCreated = 0;
        int nmethodsLive = 0;
        int[] attempts = new int[32];
        double regallocTime = 0;
        int maxattempts = 0;

        LinkedHashMap<String, Double> phaseTime = new LinkedHashMap<String, Double>(7);
        LinkedHashMap<String, Integer> phaseNodes = new LinkedHashMap<String, Integer>(7);
        double elapsed = 0;

        for (LogEvent e : events) {
            if (e instanceof Compilation) {
                Compilation c = (Compilation) e;
                c.printShort(out);
                out.printf(" %6.4f\n", c.getElapsedTime());
                attempts[c.getAttempts()]++;
                maxattempts = Math.max(maxattempts,c.getAttempts());
                elapsed += c.getElapsedTime();
                for (Phase phase : c.getPhases()) {
                    Double v = phaseTime.get(phase.getName());
                    if (v == null) {
                        v = Double.valueOf(0.0);
                    }
                    phaseTime.put(phase.getName(), Double.valueOf(v.doubleValue() + phase.getElapsedTime()));

                    Integer v2 = phaseNodes.get(phase.getName());
                    if (v2 == null) {
                        v2 = Integer.valueOf(0);
                    }
                    phaseNodes.put(phase.getName(), Integer.valueOf(v2.intValue() + phase.getNodes()));
                    /* Print phase name, elapsed time, nodes at the start of the phase,
                       nodes created in the phase, live nodes at the start of the phase,
                       live nodes added in the phase.
                    */
                    // out.printf("\t%s %6.4f %d %d %d %d\n", phase.getName(), phase.getElapsedTime(), phase.getStartNodes(), phase.getNodes(), phase.getStartLiveNodes(), phase.getLiveNodes());
                }
            } else if (e instanceof MakeNotEntrantEvent) {
                MakeNotEntrantEvent mne = (MakeNotEntrantEvent) e;
                NMethod nm = mne.getNMethod();
                if (mne.isZombie()) {
                    if (nm == null) {
                        System.err.println(mne.getId());
                    }
                    cacheSize -= nm.getSize();
                    nmethodsLive--;
                }
            } else if (e instanceof NMethod) {
                nmethodsLive++;
                nmethodsCreated++;
                NMethod nm = (NMethod) e;
                cacheSize += nm.getSize();
                maxCacheSize = Math.max(cacheSize, maxCacheSize);
            }
        }
        out.printf("NMethods: %d created %d live %d bytes (%d peak) in the code cache\n",
                          nmethodsCreated, nmethodsLive, cacheSize, maxCacheSize);
        out.println("Phase times:");
        for (String name : phaseTime.keySet()) {
            Double v = phaseTime.get(name);
            Integer v2 = phaseNodes.get(name);
            out.printf("%20s %6.4f %d\n", name, v.doubleValue(), v2.intValue());
        }
        out.printf("%20s %6.4f\n", "total", elapsed);

        if (maxattempts > 0) {
            out.println("Distribution of regalloc passes:");
            for (int i = 0; i <= maxattempts; i++) {
                out.printf("%2d %8d\n", i, attempts[i]);
            }
        }
    }
}
