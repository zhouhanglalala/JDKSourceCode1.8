/*
 * Copyright (c) 2000, 2013, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
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
 */

/*
 *
 *  (C) Copyright IBM Corp. 1999 All Rights Reserved.
 *  Copyright 1997 The Open Group Research Institute.  All rights reserved.
 */
package sun.security.krb5;

import java.io.File;
import java.io.FileInputStream;
import java.util.Hashtable;
import java.util.Vector;
import java.util.ArrayList;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;
import java.util.StringTokenizer;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.AccessController;
import java.security.PrivilegedExceptionAction;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import sun.net.dns.ResolverConfiguration;
import sun.security.krb5.internal.crypto.EType;
import sun.security.krb5.internal.Krb5;

/**
 * This class maintains key-value pairs of Kerberos configurable constants
 * from configuration file or from user specified system properties.
 */

public class Config {

    /*
     * Only allow a single instance of Config.
     */
    private static Config singleton = null;

    /*
     * Hashtable used to store configuration information.
     */
    private Hashtable<String,Object> stanzaTable = new Hashtable<>();

    private static boolean DEBUG = Krb5.DEBUG;

    // these are used for hexdecimal calculation.
    private static final int BASE16_0 = 1;
    private static final int BASE16_1 = 16;
    private static final int BASE16_2 = 16 * 16;
    private static final int BASE16_3 = 16 * 16 * 16;

    /**
     * Specified by system properties. Must be both null or non-null.
     */
    private final String defaultRealm;
    private final String defaultKDC;

    // used for native interface
    private static native String getWindowsDirectory(boolean isSystem);


    /**
     * Gets an instance of Config class. One and only one instance (the
     * singleton) is returned.
     *
     * @exception KrbException if error occurs when constructing a Config
     * instance. Possible causes would be either of java.security.krb5.realm or
     * java.security.krb5.kdc not specified, error reading configuration file.
     */
    public static synchronized Config getInstance() throws KrbException {
        if (singleton == null) {
            singleton = new Config();
        }
        return singleton;
    }

    /**
     * Refresh and reload the Configuration. This could involve,
     * for example reading the Configuration file again or getting
     * the java.security.krb5.* system properties again. This method
     * also tries its best to update static fields in other classes
     * that depend on the configuration.
     *
     * @exception KrbException if error occurs when constructing a Config
     * instance. Possible causes would be either of java.security.krb5.realm or
     * java.security.krb5.kdc not specified, error reading configuration file.
     */

    public static synchronized void refresh() throws KrbException {
        singleton = new Config();
        KdcComm.initStatic();
        EType.initStatic();
        Checksum.initStatic();
    }


    private static boolean isMacosLionOrBetter() {
        // split the "10.x.y" version number
        String osname = getProperty("os.name");
        if (!osname.contains("OS X")) {
            return false;
        }

        String osVersion = getProperty("os.version");
        String[] fragments = osVersion.split("\\.");

        // sanity check the "10." part of the version
        if (!fragments[0].equals("10")) return false;
        if (fragments.length < 2) return false;

        // check if Mac OS X 10.7(.y)
        try {
            int minorVers = Integer.parseInt(fragments[1]);
            if (minorVers >= 7) return true;
        } catch (NumberFormatException e) {
            // was not an integer
        }

        return false;
    }

    /**
     * Private constructor - can not be instantiated externally.
     */
    private Config() throws KrbException {
        /*
         * If either one system property is specified, we throw exception.
         */
        String tmp = getProperty("java.security.krb5.kdc");
        if (tmp != null) {
            // The user can specify a list of kdc hosts separated by ":"
            defaultKDC = tmp.replace(':', ' ');
        } else {
            defaultKDC = null;
        }
        defaultRealm = getProperty("java.security.krb5.realm");
        if ((defaultKDC == null && defaultRealm != null) ||
            (defaultRealm == null && defaultKDC != null)) {
            throw new KrbException
                ("System property java.security.krb5.kdc and " +
                 "java.security.krb5.realm both must be set or " +
                 "neither must be set.");
        }

        // Always read the Kerberos configuration file
        try {
            List<String> configFile;
            String fileName = getJavaFileName();
            if (fileName != null) {
                configFile = loadConfigFile(fileName);
                stanzaTable = parseStanzaTable(configFile);
                if (DEBUG) {
                    System.out.println("Loaded from Java config");
                }
            } else {
                boolean found = false;
                if (isMacosLionOrBetter()) {
                    try {
                        stanzaTable = SCDynamicStoreConfig.getConfig();
                        if (DEBUG) {
                            System.out.println("Loaded from SCDynamicStoreConfig");
                        }
                        found = true;
                    } catch (IOException ioe) {
                        // OK. Will go on with file
                    }
                }
                if (!found) {
                    fileName = getNativeFileName();
                    configFile = loadConfigFile(fileName);
                    stanzaTable = parseStanzaTable(configFile);
                    if (DEBUG) {
                        System.out.println("Loaded from native config");
                    }
                }
            }
        } catch (IOException ioe) {
            // I/O error, mostly like krb5.conf missing.
            // No problem. We'll use DNS or system property etc.
        }
    }

    /**
     * Gets the last-defined string value for the specified keys.
     * @param keys the keys, as an array from section name, sub-section names
     * (if any), to value name.
     * @return the value. When there are multiple values for the same key,
     * returns the last one. {@code null} is returned if not all the keys are
     * defined. For example, {@code get("libdefaults", "forwardable")} will
     * return null if "forwardable" is not defined in [libdefaults], and
     * {@code get("realms", "R", "kdc")} will return null if "R" is not
     * defined in [realms] or "kdc" is not defined for "R".
     * @throws IllegalArgumentException if any of the keys is illegal, either
     * because a key not the last one is not a (sub)section name or the last
     * key is still a section name. For example, {@code get("libdefaults")}
     * throws this exception because [libdefaults] is a section name instead of
     * a value name, and {@code get("libdefaults", "forwardable", "tail")}
     * also throws this exception because "forwardable" is already a value name
     * and has no sub-key at all (given "forwardable" is defined, otherwise,
     * this method has no knowledge if it's a value name or a section name),
     */
    public String get(String... keys) {
        Vector<String> v = getString0(keys);
        if (v == null) return null;
        return v.lastElement();
    }

    /**
     * Gets all values for the specified keys.
     * @throws IllegalArgumentException if any of the keys is illegal
     *         (See {@link #get})
     */
    public String getAll(String... keys) {
        Vector<String> v = getString0(keys);
        if (v == null) return null;
        StringBuilder sb = new StringBuilder();
        boolean first = true;
        for (String s: v) {
            if (first) {
                sb.append(s);
                first = false;
            } else {
                sb.append(' ').append(s);
            }
        }
        return sb.toString();
    }

    /**
     * Returns true if keys exists, can be either final string(s) or sub-stanza
     * @throws IllegalArgumentException if any of the keys is illegal
     *         (See {@link #get})
     */
    public boolean exists(String... keys) {
        return get0(keys) != null;
    }

    // Returns final string value(s) for given keys.
    @SuppressWarnings("unchecked")
    private Vector<String> getString0(String... keys) {
        try {
            return (Vector<String>)get0(keys);
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException(cce);
        }
    }

    // Internal method. Returns the value for keys, which can be a sub-stanza
    // or final string value(s).
    // The only method (except for toString) that reads stanzaTable directly.
    @SuppressWarnings("unchecked")
    private Object get0(String... keys) {
        Object current = stanzaTable;
        try {
            for (String key: keys) {
                current = ((Hashtable<String,Object>)current).get(key);
                if (current == null) return null;
            }
            return current;
        } catch (ClassCastException cce) {
            throw new IllegalArgumentException(cce);
        }
    }

    /**
     * Gets the int value for the specified keys.
     * @param keys the keys
     * @return the int value, Integer.MIN_VALUE is returned if it cannot be
     * found or the value is not a legal integer.
     * @throw IllegalArgumentException if any of the keys is illegal
     * @see #get(String[])
     */
    public int getIntValue(String... keys) {
        String result = get(keys);
        int value = Integer.MIN_VALUE;
        if (result != null) {
            try {
                value = parseIntValue(result);
            } catch (NumberFormatException e) {
                if (DEBUG) {
                    System.out.println("Exception in getting value of " +
                                       Arrays.toString(keys) + " " +
                                       e.getMessage());
                    System.out.println("Setting " + Arrays.toString(keys) +
                                       " to minimum value");
                }
                value = Integer.MIN_VALUE;
            }
        }
        return value;
    }

    /**
     * Gets the boolean value for the specified keys.
     * @param keys the keys
     * @return the boolean value, false is returned if it cannot be
     * found or the value is not "true" (case insensitive).
     * @throw IllegalArgumentException if any of the keys is illegal
     * @see #get(String[])
     */
    public boolean getBooleanValue(String... keys) {
        String val = get(keys);
        if (val != null && val.equalsIgnoreCase("true")) {
            return true;
        } else {
            return false;
        }
    }

    /**
     * Parses a string to an integer. The convertible strings include the
     * string representations of positive integers, negative integers, and
     * hex decimal integers.  Valid inputs are, e.g., -1234, +1234,
     * 0x40000.
     *
     * @param input the String to be converted to an Integer.
     * @return an numeric value represented by the string
     * @exception NumberFormationException if the String does not contain a
     * parsable integer.
     */
    private int parseIntValue(String input) throws NumberFormatException {
        int value = 0;
        if (input.startsWith("+")) {
            String temp = input.substring(1);
            return Integer.parseInt(temp);
        } else if (input.startsWith("0x")) {
            String temp = input.substring(2);
            char[] chars = temp.toCharArray();
            if (chars.length > 8) {
                throw new NumberFormatException();
            } else {
                for (int i = 0; i < chars.length; i++) {
                    int index = chars.length - i - 1;
                    switch (chars[i]) {
                    case '0':
                        value += 0;
                        break;
                    case '1':
                        value += 1 * getBase(index);
                        break;
                    case '2':
                        value += 2 * getBase(index);
                        break;
                    case '3':
                        value += 3 * getBase(index);
                        break;
                    case '4':
                        value += 4 * getBase(index);
                        break;
                    case '5':
                        value += 5 * getBase(index);
                        break;
                    case '6':
                        value += 6 * getBase(index);
                        break;
                    case '7':
                        value += 7 * getBase(index);
                        break;
                    case '8':
                        value += 8 * getBase(index);
                        break;
                    case '9':
                        value += 9 * getBase(index);
                        break;
                    case 'a':
                    case 'A':
                        value += 10 * getBase(index);
                        break;
                    case 'b':
                    case 'B':
                        value += 11 * getBase(index);
                        break;
                    case 'c':
                    case 'C':
                        value += 12 * getBase(index);
                        break;
                    case 'd':
                    case 'D':
                        value += 13 * getBase(index);
                        break;
                    case 'e':
                    case 'E':
                        value += 14 * getBase(index);
                        break;
                    case 'f':
                    case 'F':
                        value += 15 * getBase(index);
                        break;
                    default:
                        throw new NumberFormatException("Invalid numerical format");
                    }
                }
            }
            if (value < 0) {
                throw new NumberFormatException("Data overflow.");
            }
        } else {
            value = Integer.parseInt(input);
        }
        return value;
    }

    private int getBase(int i) {
        int result = 16;
        switch (i) {
        case 0:
            result = BASE16_0;
            break;
        case 1:
            result = BASE16_1;
            break;
        case 2:
            result = BASE16_2;
            break;
        case 3:
            result = BASE16_3;
            break;
        default:
            for (int j = 1; j < i; j++) {
                result *= 16;
            }
        }
        return result;
    }

    /**
     * Reads lines to the memory from the configuration file.
     *
     * Configuration file contains information about the default realm,
     * ticket parameters, location of the KDC and the admin server for
     * known realms, etc. The file is divided into sections. Each section
     * contains one or more name/value pairs with one pair per line. A
     * typical file would be:
     * <pre>
     * [libdefaults]
     *          default_realm = EXAMPLE.COM
     *          default_tgs_enctypes = des-cbc-md5
     *          default_tkt_enctypes = des-cbc-md5
     * [realms]
     *          EXAMPLE.COM = {
     *                  kdc = kerberos.example.com
     *                  kdc = kerberos-1.example.com
     *                  admin_server = kerberos.example.com
     *                  }
     *          SAMPLE_COM = {
     *                  kdc = orange.sample.com
     *                  admin_server = orange.sample.com
     *                  }
     * [domain_realm]
     *          blue.sample.com = TEST.SAMPLE.COM
     *          .backup.com     = EXAMPLE.COM
     * </pre>
     * @return an ordered list of strings representing the config file after
     * some initial processing, including:<ol>
     * <li> Comment lines and empty lines are removed
     * <li> "{" not at the end of a line is appended to the previous line
     * <li> The content of a section is also placed between "{" and "}".
     * <li> Lines are trimmed</ol>
     * @throws IOException if there is an I/O error
     * @throws KrbException if there is a file format error
     */
    private List<String> loadConfigFile(final String fileName)
            throws IOException, KrbException {
        try {
            List<String> v = new ArrayList<>();
            try (BufferedReader br = new BufferedReader(new InputStreamReader(
                AccessController.doPrivileged(
                    new PrivilegedExceptionAction<FileInputStream> () {
                        public FileInputStream run() throws IOException {
                            return new FileInputStream(fileName);
                        }
                    })))) {
                String line;
                String previous = null;
                while ((line = br.readLine()) != null) {
                    line = line.trim();
                    if (line.startsWith("#") || line.isEmpty()) {
                        // ignore comments and blank line
                        // Comments start with #.
                        continue;
                    }
                    // In practice, a subsection might look like:
                    //      [realms]
                    //      EXAMPLE.COM =
                    //      {
                    //          kdc = kerberos.example.com
                    //          ...
                    //      }
                    // Before parsed into stanza table, it needs to be
                    // converted into a canonicalized style (no indent):
                    //      realms = {
                    //          EXAMPLE.COM = {
                    //              kdc = kerberos.example.com
                    //              ...
                    //          }
                    //      }
                    //
                    if (line.startsWith("[")) {
                        if (!line.endsWith("]")) {
                            throw new KrbException("Illegal config content:"
                                    + line);
                        }
                        if (previous != null) {
                            v.add(previous);
                            v.add("}");
                        }
                        String title = line.substring(
                                1, line.length()-1).trim();
                        if (title.isEmpty()) {
                            throw new KrbException("Illegal config content:"
                                    + line);
                        }
                        previous = title + " = {";
                    } else if (line.startsWith("{")) {
                        if (previous == null) {
                            throw new KrbException(
                                "Config file should not start with \"{\"");
                        }
                        previous += " {";
                        if (line.length() > 1) {
                            // { and content on the same line
                            v.add(previous);
                            previous = line.substring(1).trim();
                        }
                    } else {
                        // Lines before the first section are ignored
                        if (previous != null) {
                            v.add(previous);
                            previous = line;
                        }
                    }
                }
                if (previous != null) {
                    v.add(previous);
                    v.add("}");
                }
            }
            return v;
        } catch (java.security.PrivilegedActionException pe) {
            throw (IOException)pe.getException();
        }
    }

    /**
     * Parses stanza names and values from configuration file to
     * stanzaTable (Hashtable). Hashtable key would be stanza names,
     * (libdefaults, realms, domain_realms, etc), and the hashtable value
     * would be another hashtable which contains the key-value pairs under
     * a stanza name. The value of this sub-hashtable can be another hashtable
     * containing another sub-sub-section or a vector of strings for
     * final values (even if there is only one value defined).
     * <p>
     * For duplicates section names, the latter overwrites the former. For
     * duplicate value names, the values are in a vector in its appearing order.
     * </ol>
     * Please note that this behavior is Java traditional. and it is
     * not the same as the MIT krb5 behavior, where:<ol>
     * <li>Duplicated root sections will be merged
     * <li>For duplicated sub-sections, the former overwrites the latter
     * <li>Duplicate keys for values are always saved in a vector
     * </ol>
     * @param v the strings in the file, never null, might be empty
     * @throws KrbException if there is a file format error
     */
    @SuppressWarnings("unchecked")
    private Hashtable<String,Object> parseStanzaTable(List<String> v)
            throws KrbException {
        Hashtable<String,Object> current = stanzaTable;
        for (String line: v) {
            // There are 3 kinds of lines
            // 1. a = b
            // 2. a = {
            // 3. }
            if (line.equals("}")) {
                // Go back to parent, see below
                current = (Hashtable<String,Object>)current.remove(" PARENT ");
                if (current == null) {
                    throw new KrbException("Unmatched close brace");
                }
            } else {
                int pos = line.indexOf('=');
                if (pos < 0) {
                    throw new KrbException("Illegal config content:" + line);
                }
                String key = line.substring(0, pos).trim();
                String value = trimmed(line.substring(pos+1));
                if (value.equals("{")) {
                    Hashtable<String,Object> subTable;
                    if (current == stanzaTable) {
                        key = key.toLowerCase(Locale.US);
                    }
                    subTable = new Hashtable<>();
                    current.put(key, subTable);
                    // A special entry for its parent. Put whitespaces around,
                    // so will never be confused with a normal key
                    subTable.put(" PARENT ", current);
                    current = subTable;
                } else {
                    Vector<String> values;
                    if (current.containsKey(key)) {
                        Object obj = current.get(key);
                        // If a key first shows as a section and then a value,
                        // this is illegal. However, we haven't really forbid
                        // first value then section, which the final result
                        // is a section.
                        if (!(obj instanceof Vector)) {
                            throw new KrbException("Key " + key
                                    + "used for both value and section");
                        }
                        values = (Vector<String>)current.get(key);
                    } else {
                        values = new Vector<String>();
                        current.put(key, values);
                    }
                    values.add(value);
                }
            }
        }
        if (current != stanzaTable) {
            throw new KrbException("Not closed");
        }
        return current;
    }

    /**
     * Gets the default Java configuration file name.
     *
     * If the system property "java.security.krb5.conf" is defined, we'll
     * use its value, no matter if the file exists or not. Otherwise, we
     * will look at $JAVA_HOME/lib/security directory with "krb5.conf" name,
     * and return it if the file exists.
     *
     * The method returns null if it cannot find a Java config file.
     */
    private String getJavaFileName() {
        String name = getProperty("java.security.krb5.conf");
        if (name == null) {
            name = getProperty("java.home") + File.separator +
                                "lib" + File.separator + "security" +
                                File.separator + "krb5.conf";
            if (!fileExists(name)) {
                name = null;
            }
        }
        if (DEBUG) {
            System.out.println("Java config name: " + name);
        }
        return name;
    }

    /**
     * Gets the default native configuration file name.
     *
     * Depending on the OS type, the method returns the default native
     * kerberos config file name, which is at windows directory with
     * the name of "krb5.ini" for Windows, /etc/krb5/krb5.conf for Solaris,
     * /etc/krb5.conf otherwise. Mac OSX X has a different file name.
     *
     * Note: When the Terminal Service is started in Windows (from 2003),
     * there are two kinds of Windows directories: A system one (say,
     * C:\Windows), and a user-private one (say, C:\Users\Me\Windows).
     * We will first look for krb5.ini in the user-private one. If not
     * found, try the system one instead.
     *
     * This method will always return a non-null non-empty file name,
     * even if that file does not exist.
     */
    private String getNativeFileName() {
        String name = null;
        String osname = getProperty("os.name");
        if (osname.startsWith("Windows")) {
            try {
                Credentials.ensureLoaded();
            } catch (Exception e) {
                // ignore exceptions
            }
            if (Credentials.alreadyLoaded) {
                String path = getWindowsDirectory(false);
                if (path != null) {
                    if (path.endsWith("\\")) {
                        path = path + "krb5.ini";
                    } else {
                        path = path + "\\krb5.ini";
                    }
                    if (fileExists(path)) {
                        name = path;
                    }
                }
                if (name == null) {
                    path = getWindowsDirectory(true);
                    if (path != null) {
                        if (path.endsWith("\\")) {
                            path = path + "krb5.ini";
                        } else {
                            path = path + "\\krb5.ini";
                        }
                        name = path;
                    }
                }
            }
            if (name == null) {
                name = "c:\\winnt\\krb5.ini";
            }
        } else if (osname.startsWith("SunOS")) {
            name =  "/etc/krb5/krb5.conf";
        } else if (osname.contains("OS X")) {
            name = findMacosConfigFile();
        } else {
            name =  "/etc/krb5.conf";
        }
        if (DEBUG) {
            System.out.println("Native config name: " + name);
        }
        return name;
    }

    private static String getProperty(String property) {
        return AccessController.doPrivileged(
                new sun.security.action.GetPropertyAction(property));
    }

    private String findMacosConfigFile() {
        String userHome = getProperty("user.home");
        final String PREF_FILE = "/Library/Preferences/edu.mit.Kerberos";
        String userPrefs = userHome + PREF_FILE;

        if (fileExists(userPrefs)) {
            return userPrefs;
        }

        if (fileExists(PREF_FILE)) {
            return PREF_FILE;
        }

        return "/etc/krb5.conf";
    }

    private static String trimmed(String s) {
        s = s.trim();
        if (s.isEmpty()) return s;
        if (s.charAt(0) == '"' && s.charAt(s.length()-1) == '"' ||
                s.charAt(0) == '\'' && s.charAt(s.length()-1) == '\'') {
            s = s.substring(1, s.length()-1).trim();
        }
        return s;
    }

    /**
     * For testing purpose. This method lists all information being parsed from
     * the configuration file to the hashtable.
     */
    public void listTable() {
        System.out.println(this);
    }

    /**
     * Returns all etypes specified in krb5.conf for the given configName,
     * or all the builtin defaults. This result is always non-empty.
     * If no etypes are found, an exception is thrown.
     */
    public int[] defaultEtype(String configName) throws KrbException {
        String default_enctypes;
        default_enctypes = get("libdefaults", configName);
        int[] etype;
        if (default_enctypes == null) {
            if (DEBUG) {
                System.out.println("Using builtin default etypes for " +
                    configName);
            }
            etype = EType.getBuiltInDefaults();
        } else {
            String delim = " ";
            StringTokenizer st;
            for (int j = 0; j < default_enctypes.length(); j++) {
                if (default_enctypes.substring(j, j + 1).equals(",")) {
                    // only two delimiters are allowed to use
                    // according to Kerberos DCE doc.
                    delim = ",";
                    break;
                }
            }
            st = new StringTokenizer(default_enctypes, delim);
            int len = st.countTokens();
            ArrayList<Integer> ls = new ArrayList<>(len);
            int type;
            for (int i = 0; i < len; i++) {
                type = Config.getType(st.nextToken());
                if (type != -1 && EType.isSupported(type)) {
                    ls.add(type);
                }
            }
            if (ls.isEmpty()) {
                throw new KrbException("no supported default etypes for "
                        + configName);
            } else {
                etype = new int[ls.size()];
                for (int i = 0; i < etype.length; i++) {
                    etype[i] = ls.get(i);
                }
            }
        }

        if (DEBUG) {
            System.out.print("default etypes for " + configName + ":");
            for (int i = 0; i < etype.length; i++) {
                System.out.print(" " + etype[i]);
            }
            System.out.println(".");
        }
        return etype;
    }


    /**
     * Get the etype and checksum value for the specified encryption and
     * checksum type.
     *
     */
    /*
     * This method converts the string representation of encryption type and
     * checksum type to int value that can be later used by EType and
     * Checksum classes.
     */
    public static int getType(String input) {
        int result = -1;
        if (input == null) {
            return result;
        }
        if (input.startsWith("d") || (input.startsWith("D"))) {
            if (input.equalsIgnoreCase("des-cbc-crc")) {
                result = EncryptedData.ETYPE_DES_CBC_CRC;
            } else if (input.equalsIgnoreCase("des-cbc-md5")) {
                result = EncryptedData.ETYPE_DES_CBC_MD5;
            } else if (input.equalsIgnoreCase("des-mac")) {
                result = Checksum.CKSUMTYPE_DES_MAC;
            } else if (input.equalsIgnoreCase("des-mac-k")) {
                result = Checksum.CKSUMTYPE_DES_MAC_K;
            } else if (input.equalsIgnoreCase("des-cbc-md4")) {
                result = EncryptedData.ETYPE_DES_CBC_MD4;
            } else if (input.equalsIgnoreCase("des3-cbc-sha1") ||
                input.equalsIgnoreCase("des3-hmac-sha1") ||
                input.equalsIgnoreCase("des3-cbc-sha1-kd") ||
                input.equalsIgnoreCase("des3-cbc-hmac-sha1-kd")) {
                result = EncryptedData.ETYPE_DES3_CBC_HMAC_SHA1_KD;
            }
        } else if (input.startsWith("a") || (input.startsWith("A"))) {
            // AES
            if (input.equalsIgnoreCase("aes128-cts") ||
                input.equalsIgnoreCase("aes128-cts-hmac-sha1-96")) {
                result = EncryptedData.ETYPE_AES128_CTS_HMAC_SHA1_96;
            } else if (input.equalsIgnoreCase("aes256-cts") ||
                input.equalsIgnoreCase("aes256-cts-hmac-sha1-96")) {
                result = EncryptedData.ETYPE_AES256_CTS_HMAC_SHA1_96;
            // ARCFOUR-HMAC
            } else if (input.equalsIgnoreCase("arcfour-hmac") ||
                   input.equalsIgnoreCase("arcfour-hmac-md5")) {
                result = EncryptedData.ETYPE_ARCFOUR_HMAC;
            }
        // RC4-HMAC
        } else if (input.equalsIgnoreCase("rc4-hmac")) {
            result = EncryptedData.ETYPE_ARCFOUR_HMAC;
        } else if (input.equalsIgnoreCase("CRC32")) {
            result = Checksum.CKSUMTYPE_CRC32;
        } else if (input.startsWith("r") || (input.startsWith("R"))) {
            if (input.equalsIgnoreCase("rsa-md5")) {
                result = Checksum.CKSUMTYPE_RSA_MD5;
            } else if (input.equalsIgnoreCase("rsa-md5-des")) {
                result = Checksum.CKSUMTYPE_RSA_MD5_DES;
            }
        } else if (input.equalsIgnoreCase("hmac-sha1-des3-kd")) {
            result = Checksum.CKSUMTYPE_HMAC_SHA1_DES3_KD;
        } else if (input.equalsIgnoreCase("hmac-sha1-96-aes128")) {
            result = Checksum.CKSUMTYPE_HMAC_SHA1_96_AES128;
        } else if (input.equalsIgnoreCase("hmac-sha1-96-aes256")) {
            result = Checksum.CKSUMTYPE_HMAC_SHA1_96_AES256;
        } else if (input.equalsIgnoreCase("hmac-md5-rc4") ||
                input.equalsIgnoreCase("hmac-md5-arcfour") ||
                input.equalsIgnoreCase("hmac-md5-enc")) {
            result = Checksum.CKSUMTYPE_HMAC_MD5_ARCFOUR;
        } else if (input.equalsIgnoreCase("NULL")) {
            result = EncryptedData.ETYPE_NULL;
        }

        return result;
    }

    /**
     * Resets the default kdc realm.
     * We do not need to synchronize these methods since assignments are atomic
     *
     * This method was useless. Kept here in case some class still calls it.
     */
    public void resetDefaultRealm(String realm) {
        if (DEBUG) {
            System.out.println(">>> Config try resetting default kdc " + realm);
        }
    }

    /**
     * Check to use addresses in tickets
     * use addresses if "no_addresses" or "noaddresses" is set to false
     */
    public boolean useAddresses() {
        boolean useAddr = false;
        // use addresses if "no_addresses" is set to false
        String value = get("libdefaults", "no_addresses");
        useAddr = (value != null && value.equalsIgnoreCase("false"));
        if (useAddr == false) {
            // use addresses if "noaddresses" is set to false
            value = get("libdefaults", "noaddresses");
            useAddr = (value != null && value.equalsIgnoreCase("false"));
        }
        return useAddr;
    }

    /**
     * Check if need to use DNS to locate Kerberos services
     */
    private boolean useDNS(String name) {
        String value = get("libdefaults", name);
        if (value == null) {
            value = get("libdefaults", "dns_fallback");
            if ("false".equalsIgnoreCase(value)) {
                return false;
            } else {
                return true;
            }
        } else {
            return value.equalsIgnoreCase("true");
        }
    }

    /**
     * Check if need to use DNS to locate the KDC
     */
    private boolean useDNS_KDC() {
        return useDNS("dns_lookup_kdc");
    }

    /*
     * Check if need to use DNS to locate the Realm
     */
    private boolean useDNS_Realm() {
        return useDNS("dns_lookup_realm");
    }

    /**
     * Gets default realm.
     * @throws KrbException where no realm can be located
     * @return the default realm, always non null
     */
    public String getDefaultRealm() throws KrbException {
        if (defaultRealm != null) {
            return defaultRealm;
        }
        Exception cause = null;
        String realm = get("libdefaults", "default_realm");
        if ((realm == null) && useDNS_Realm()) {
            // use DNS to locate Kerberos realm
            try {
                realm = getRealmFromDNS();
            } catch (KrbException ke) {
                cause = ke;
            }
        }
        if (realm == null) {
            realm = AccessController.doPrivileged(
                    new java.security.PrivilegedAction<String>() {
                @Override
                public String run() {
                    String osname = System.getProperty("os.name");
                    if (osname.startsWith("Windows")) {
                        return System.getenv("USERDNSDOMAIN");
                    }
                    return null;
                }
            });
        }
        if (realm == null) {
            KrbException ke = new KrbException("Cannot locate default realm");
            if (cause != null) {
                ke.initCause(cause);
            }
            throw ke;
        }
        return realm;
    }

    /**
     * Returns a list of KDC's with each KDC separated by a space
     *
     * @param realm the realm for which the KDC list is desired
     * @throws KrbException if there's no way to find KDC for the realm
     * @return the list of KDCs separated by a space, always non null
     */
    public String getKDCList(String realm) throws KrbException {
        if (realm == null) {
            realm = getDefaultRealm();
        }
        if (realm.equalsIgnoreCase(defaultRealm)) {
            return defaultKDC;
        }
        Exception cause = null;
        String kdcs = getAll("realms", realm, "kdc");
        if ((kdcs == null) && useDNS_KDC()) {
            // use DNS to locate KDC
            try {
                kdcs = getKDCFromDNS(realm);
            } catch (KrbException ke) {
                cause = ke;
            }
        }
        if (kdcs == null) {
            kdcs = AccessController.doPrivileged(
                    new java.security.PrivilegedAction<String>() {
                @Override
                public String run() {
                    String osname = System.getProperty("os.name");
                    if (osname.startsWith("Windows")) {
                        String logonServer = System.getenv("LOGONSERVER");
                        if (logonServer != null
                                && logonServer.startsWith("\\\\")) {
                            logonServer = logonServer.substring(2);
                        }
                        return logonServer;
                    }
                    return null;
                }
            });
        }
        if (kdcs == null) {
            if (defaultKDC != null) {
                return defaultKDC;
            }
            KrbException ke = new KrbException("Cannot locate KDC");
            if (cause != null) {
                ke.initCause(cause);
            }
            throw ke;
        }
        return kdcs;
    }

    /**
     * Locate Kerberos realm using DNS
     *
     * @return the Kerberos realm
     */
    private String getRealmFromDNS() throws KrbException {
        // use DNS to locate Kerberos realm
        String realm = null;
        String hostName = null;
        try {
            hostName = InetAddress.getLocalHost().getCanonicalHostName();
        } catch (UnknownHostException e) {
            KrbException ke = new KrbException(Krb5.KRB_ERR_GENERIC,
                "Unable to locate Kerberos realm: " + e.getMessage());
            ke.initCause(e);
            throw (ke);
        }
        // get the domain realm mapping from the configuration
        String mapRealm = PrincipalName.mapHostToRealm(hostName);
        if (mapRealm == null) {
            // No match. Try search and/or domain in /etc/resolv.conf
            List<String> srchlist = ResolverConfiguration.open().searchlist();
            for (String domain: srchlist) {
                realm = checkRealm(domain);
                if (realm != null) {
                    break;
                }
            }
        } else {
            realm = checkRealm(mapRealm);
        }
        if (realm == null) {
            throw new KrbException(Krb5.KRB_ERR_GENERIC,
                                "Unable to locate Kerberos realm");
        }
        return realm;
    }

    /**
     * Check if the provided realm is the correct realm
     * @return the realm if correct, or null otherwise
     */
    private static String checkRealm(String mapRealm) {
        if (DEBUG) {
            System.out.println("getRealmFromDNS: trying " + mapRealm);
        }
        String[] records = null;
        String newRealm = mapRealm;
        while ((records == null) && (newRealm != null)) {
            // locate DNS TXT record
            records = KrbServiceLocator.getKerberosService(newRealm);
            newRealm = Realm.parseRealmComponent(newRealm);
            // if no DNS TXT records found, try again using sub-realm
        }
        if (records != null) {
            for (int i = 0; i < records.length; i++) {
                if (records[i].equalsIgnoreCase(mapRealm)) {
                    return records[i];
                }
            }
        }
        return null;
    }

    /**
     * Locate KDC using DNS
     *
     * @param realm the realm for which the master KDC is desired
     * @return the KDC
     */
    private String getKDCFromDNS(String realm) throws KrbException {
        // use DNS to locate KDC
        String kdcs = "";
        String[] srvs = null;
        // locate DNS SRV record using UDP
        if (DEBUG) {
            System.out.println("getKDCFromDNS using UDP");
        }
        srvs = KrbServiceLocator.getKerberosService(realm, "_udp");
        if (srvs == null) {
            // locate DNS SRV record using TCP
            if (DEBUG) {
                System.out.println("getKDCFromDNS using TCP");
            }
            srvs = KrbServiceLocator.getKerberosService(realm, "_tcp");
        }
        if (srvs == null) {
            // no DNS SRV records
            throw new KrbException(Krb5.KRB_ERR_GENERIC,
                "Unable to locate KDC for realm " + realm);
        }
        if (srvs.length == 0) {
            return null;
        }
        for (int i = 0; i < srvs.length; i++) {
            kdcs += srvs[i].trim() + " ";
        }
        kdcs = kdcs.trim();
        if (kdcs.equals("")) {
            return null;
        }
        return kdcs;
    }

    private boolean fileExists(String name) {
        return AccessController.doPrivileged(
                                new FileExistsAction(name));
    }

    static class FileExistsAction
        implements java.security.PrivilegedAction<Boolean> {

        private String fileName;

        public FileExistsAction(String fileName) {
            this.fileName = fileName;
        }

        public Boolean run() {
            return new File(fileName).exists();
        }
    }

    // Shows the content of the Config object for debug purpose.
    //
    // {
    //      libdefaults = {
    //          default_realm = R
    //      }
    //      realms = {
    //          R = {
    //              kdc = [k1,k2]
    //          }
    //      }
    // }

    @Override
    public String toString() {
        StringBuffer sb = new StringBuffer();
        toStringInternal("", stanzaTable, sb);
        return sb.toString();
    }
    private static void toStringInternal(String prefix, Object obj,
            StringBuffer sb) {
        if (obj instanceof String) {
            // A string value, just print it
            sb.append(obj).append('\n');
        } else if (obj instanceof Hashtable) {
            // A table, start a new sub-section...
            Hashtable<?, ?> tab = (Hashtable<?, ?>)obj;
            sb.append("{\n");
            for (Object o: tab.keySet()) {
                // ...indent, print "key = ", and
                sb.append(prefix).append("    ").append(o).append(" = ");
                // ...go recursively into value
                toStringInternal(prefix + "    ", tab.get(o), sb);
            }
            sb.append(prefix).append("}\n");
        } else if (obj instanceof Vector) {
            // A vector of strings, print them inside [ and ]
            Vector<?> v = (Vector<?>)obj;
            sb.append("[");
            boolean first = true;
            for (Object o: v.toArray()) {
                if (!first) sb.append(",");
                sb.append(o);
                first = false;
            }
            sb.append("]\n");
        }
    }
}
