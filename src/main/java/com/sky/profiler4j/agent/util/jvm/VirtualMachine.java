/*
 * Copyright 2008-2014 by Emeric Vernat
 *
 *     This file is part of Java Melody.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.sky.profiler4j.agent.util.jvm;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;

/**
 * Classe d'attachement dynamique utilisée ici pour obtenir l'histogramme de la
 * mémoire. <br/>
 * Cette classe nécessite tools.jar du jdk pour être exécutée (ok dans tomcat),
 * mais pas pour être compilée. <br/>
 * 
 * @see <a href=
 *      "http://java.sun.com/javase/6/docs/jdk/api/attach/spec/com/sun/tools/attach/VirtualMachine.html#attach(java.lang.String)">
 *      VirtualMachine</a>
 * 
 * @author Emeric Vernat
 */
public class VirtualMachine {
	private static boolean enabled = isSupported();
	// singleton initialisé à la demande
	private static Object jvmVirtualMachine;

	private VirtualMachine() {
		super();
	}

	/**
	 * @return true si heapHisto supporté.
	 */
	static boolean isSupported() {
		// pour nodes Hudson/Jenkins, on réévalue sans utiliser de constante
		final String javaVendor = System.getProperty("java.vendor");
		return javaVendor.contains("Sun") || javaVendor.contains("Oracle") || javaVendor.contains("Apple")
				|| isJRockit();
	}

	/**
	 * @return true si JVM JRockit
	 */
	static boolean isJRockit() {
		// pour nodes Hudson/Jenkins, on réévalue sans utiliser de constante
		return System.getProperty("java.vendor").contains("BEA");
	}

	/**
	 * @return false si non supporté ou si un attachement ou un histogramme a
	 *         échoué, true si supporté et pas essayé ou si réussi
	 */
	static synchronized boolean isEnabled() { // NOPMD
		return enabled;
	}

	/**
	 * @return Singleton initialisé à la demande de l'instance de
	 *         com.sun.tools.attach.VirtualMachine, null si enabled est false
	 * @throws Exception
	 *             e
	 */
	static synchronized Object getJvmVirtualMachine() throws Exception { // NOPMD
		// si hotspot retourne une instance de
		// sun.tools.attach.HotSpotVirtualMachine
		// cf
		// http://www.java2s.com/Open-Source/Java-Document/6.0-JDK-Modules-sun/tools/sun/tools/attach/HotSpotVirtualMachine.java.htm
		// et sous windows : sun.tools.attach.WindowsVirtualMachine
		if (jvmVirtualMachine == null) {
			// on utilise la réflexion pour éviter de dépendre de tools.jar du
			// jdk à la compilation
			final Class<?> virtualMachineClass = findVirtualMachineClass();
			final Method attachMethod = virtualMachineClass.getMethod("attach", String.class);
			final String pid = PID.getPID();
			try {
				jvmVirtualMachine = invoke(attachMethod, null, pid);
			} finally {
				enabled = jvmVirtualMachine != null;
			}
		}
		return jvmVirtualMachine;
	}

	private static Class<?> findVirtualMachineClass() throws Exception { // NOPMD
		// méthode inspirée de javax.tools.ToolProvider.Lazy.findClass
		// http://grepcode.com/file/repository.grepcode.com/java/root/jdk/openjdk/6-b27/javax/tools/ToolProvider.java#ToolProvider.Lazy.findClass%28%29
		final String virtualMachineClassName = "com.sun.tools.attach.VirtualMachine";
		try {
			// try loading class directly, in case tools.jar is in the classpath
			return Class.forName(virtualMachineClassName);
		} catch (final ClassNotFoundException e) {
			// exception ignored, try looking in the default tools location
			// (lib/tools.jar)
			File file = new File(System.getProperty("java.home"));
			if ("jre".equalsIgnoreCase(file.getName())) {
				file = file.getParentFile();
			}
			final String[] defaultToolsLocation = { "lib", "tools.jar" };
			for (final String name : defaultToolsLocation) {
				file = new File(file, name);
			}
			// if tools.jar not found, no point in trying a URLClassLoader
			// so rethrow the original exception.
			if (!file.exists()) {
				throw e;
			}

			final URL url = file.toURI().toURL();
			final ClassLoader cl;
			// if (ClassLoader.getSystemClassLoader() instanceof URLClassLoader)
			// {
			// // The attachment API relies on JNI, so if we have other code in
			// the JVM that tries to use the attach API
			// // (like the monitoring of another webapp), it'll cause a failure
			// (issue 398):
			// // "UnsatisfiedLinkError: Native Library C:\Program
			// Files\Java\jdk1.6.0_35\jre\bin\attach.dll already loaded in
			// another classloader
			// // [...] com.sun.tools.attach.AttachNotSupportedException: no
			// providers installed"
			// // So we try to load tools.jar into the system classloader, so
			// that later attempts to load tools.jar will see it.
			// cl = ClassLoader.getSystemClassLoader();
			// // The URLClassLoader.addURL method is protected
			// final Method addURL =
			// URLClassLoader.class.getDeclaredMethod("addURL", URL.class);
			// addURL.setAccessible(true);
			// addURL.invoke(cl, url);
			// } else {
			final URL[] urls = { url };
			cl = URLClassLoader.newInstance(urls);
			// }
			return Class.forName(virtualMachineClassName, true, cl);
		}
	}

	/**
	 * Détachement du singleton.
	 * 
	 * @throws Exception
	 *             e
	 */
	static synchronized void detach() throws Exception { // NOPMD
		if (jvmVirtualMachine != null) {
			final Class<?> virtualMachineClass = jvmVirtualMachine.getClass();
			final Method detachMethod = virtualMachineClass.getMethod("detach");
			jvmVirtualMachine = invoke(detachMethod, jvmVirtualMachine);
			jvmVirtualMachine = null;
		}
	}

	private static Object invoke(Method method, Object object, Object... args) throws Exception { // NOPMD
		try {
			return method.invoke(object, args);
		} catch (final InvocationTargetException e) {
			if (e.getCause() instanceof Exception) {
				throw (Exception) e.getCause();
			} else if (e.getCause() instanceof Error) {
				throw (Error) e.getCause();
			} else {
				throw new Exception(e.getCause()); // NOPMD
			}
		}
	}

	/**
	 * 加载一个agent
	 * 
	 * @param jarFile
	 */
	public static void loadAgent(String jarFile) {

		if (jvmVirtualMachine == null) {
			try {
				jvmVirtualMachine = getJvmVirtualMachine();
			} catch (Exception e) {
				e.printStackTrace();
			}
		}

		try {
			final Method loadAgentMethod = jvmVirtualMachine.getClass().getMethod("loadAgent", String.class);
			jvmVirtualMachine = invoke(loadAgentMethod, jvmVirtualMachine, jarFile);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
}