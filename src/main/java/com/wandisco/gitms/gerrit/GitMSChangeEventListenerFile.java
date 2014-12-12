// Copyright (C) 2014 WANDisco
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
// http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.

package com.wandisco.gitms.gerrit;

import com.google.gerrit.common.ChangeListener;
import com.google.gerrit.extensions.annotations.Listen;
import com.google.gerrit.extensions.events.LifecycleListener;
import com.google.gerrit.server.data.ChangeAttribute;
import com.google.gerrit.server.events.ChangeEvent;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.io.UnsupportedEncodingException;
import java.lang.reflect.Field;
import java.nio.charset.Charset;
import java.util.Properties;
import org.eclipse.jgit.storage.file.FileBasedConfig;
import org.eclipse.jgit.util.FS_POSIX_Java6;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


/**
 * This plugin will capture events generated inside Gerrit and publish them as files
 * in a preconfigured directory. This configuration is extracted from the GitMS
 * application.properties file with the key "gerrit.events.basepath". The location
 * of the GitMS install is usually /opt/wandisco/git-multisite, but if this has been
 * customised it can be set through the gitmsconfig property in the Gerrit users 
 * ~/.gitconfig file.
 * 
 * This plugin is designed to only run in tandem with GitMS which will receive and
 * consume the events generated.
 */
@Listen
public class GitMSChangeEventListenerFile implements ChangeListener,LifecycleListener {
  
  private static final String GERRIT_EVENTS_KEY = "gerrit.events.basepath";
  private static final Logger log = LoggerFactory.getLogger(GitMSChangeEventListenerFile.class);
  private static final String GITMS_DEFAULT_APPLICATION_PROPERTIES = 
          "/opt/wandisco/git-multisite/replicator/properties/application.properties";
  private static File eventPath;
  private static File gerritRootPath;


  @Override
  public void onChangeEvent(ChangeEvent event) {
    
    ChangeEventDetailsHolder cedh = new ChangeEventDetailsHolder();
    PrintWriter pw = null;
    try {
      if (!setProperties(event, cedh)) {
        //change property not found, do not continue
        return;
      }
      if (!eventPath.exists()) {
          boolean mkdirs = eventPath.mkdirs();
          if (!mkdirs) {
              throw new IOException("Could not create path "+eventPath);
          }
      }
      
      long time = System.currentTimeMillis();
      File file = new File(eventPath + "/" + cedh.changeId + "-" + cedh.changeNum + "-" + time);
      pw = new PrintWriter(file,Charset.forName("UTF-8").name());
      pw.println(cedh.projectName);
    } catch (FileNotFoundException ex) {
      log.warn("Failed to find file to log changeId/projectName update: ", ex);
    } catch (UnsupportedEncodingException ex) {
      log.warn("Failed to create file to log changeId/projectName update: ", ex);
    } catch (IOException ex) {
      log.error(ex.getMessage(),ex);
    } finally {
      if (pw!=null) {
        pw.close();
      }
    }


  }

  private boolean setProperties(ChangeEvent event, ChangeEventDetailsHolder cedh) {
    
    boolean found = false;
    Field[] fields = event.getClass().getDeclaredFields();
   
    try {
      for (Field f : fields) {
        if (f.getName().equals("change")) {
          ChangeAttribute change = (ChangeAttribute) f.get(event);
          cedh.changeId = change.id;
          cedh.projectName = change.project;
          cedh.changeNum = change.number;
          found = true;
          break;
        }
      }
    } catch (IllegalAccessException e) {
      found = false;
    } 

    return found;
  }

  @Override
  public void start() {
    String gitConfigLoc = System.getenv("GIT_CONFIG");
    String applicationProperties;
    String path = GitMSChangeEventListenerFile.class.getProtectionDomain()
            .getCodeSource().getLocation().getPath();
    gerritRootPath = new File(path).getParentFile().getParentFile();
    
    log.info("Gerrit Plugin starting");
    log.debug("Jar path: " + gerritRootPath);

    if (gitConfigLoc == null) {
      gitConfigLoc = System.getProperty("user.home") + "/.gitconfig";
    }

    FileBasedConfig config = new FileBasedConfig(new File(gitConfigLoc), new FS_POSIX_Java6());
    try {
      config.load();
      
      applicationProperties = config.getString("core", null, "gitmsconfig");
      if (applicationProperties == null || applicationProperties.isEmpty()) {
        throw new Exception("Could not find application.properties location in " +
                gitConfigLoc);
      }
      
    } catch (Exception ex) {
      applicationProperties = GITMS_DEFAULT_APPLICATION_PROPERTIES;
      log.warn("Could not read property gitmsconfig in gitconfig file: " 
              + gitConfigLoc + ", using default path " + applicationProperties);
    }
    
    File appPropertiesFile = new File(applicationProperties);
    if(!appPropertiesFile.exists() || !appPropertiesFile.canRead()) {
      log.warn("Could not find/read " + applicationProperties);
      shutdownGerrit();
      return;
    }
    
    //fetch the information we need from application.properties
    Properties properties = new Properties();
    InputStream input = null;
    try {
      input = new FileInputStream(appPropertiesFile);
      properties.load(input);
      
      String gerritEventPath = properties.getProperty(GERRIT_EVENTS_KEY);
      
      eventPath = new File(gerritEventPath,"gen_events");      
    } catch (IOException ex) {
      log.warn("Error loading application.properties file: ", ex);
      shutdownGerrit();
    } finally {
      if (input != null) {
        try {
          input.close();
        } catch (IOException ex) {
           log.warn("Error while closing file",ex);
        }
      }
    }
  }
  
  private void shutdownGerrit() {
    String gerrit_sh = gerritRootPath.getAbsolutePath() + "/bin/gerrit.sh";
    final String[] argv = {gerrit_sh, "stop"};
    try {
      Runtime.getRuntime().exec(argv);
    } catch (IOException ex) {
      log.warn("Failed to shutdown Gerrit: ", ex);
    }
  }

  @Override
  public void stop() {
    log.info("Gerrit Plugin stopping");
  }
  
  private class ChangeEventDetailsHolder {
    String changeId;
    String projectName;
    String changeNum;
  }
}
