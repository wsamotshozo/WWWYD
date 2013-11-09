/*
 * Copyright (c) 2013 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.cloud.backend.pushnotification;

import com.google.appengine.api.LifecycleManager;
import com.google.appengine.api.taskqueue.Queue;
import com.google.appengine.api.taskqueue.QueueFactory;

import java.util.logging.Logger;

import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * HttpServlet for sending pending notifications.
 *
 * It is intended to be hosted on a backend
 *
 */
public class WorkerServlet extends HttpServlet {
  private static final long serialVersionUID = 1L;
  private static final Logger log = Logger.getLogger(WorkerServlet.class.getName());

  @Override
  public void doGet(HttpServletRequest req, HttpServletResponse res) {
    doPolling();
  }

  private void doPolling() {
    Queue notificationQueue = QueueFactory.getQueue("notification-delivery");

    Worker worker = new Worker(notificationQueue);
    while (true) {
      boolean continueImmediately = worker.processBatchOfTasks();

      if (LifecycleManager.getInstance().isShuttingDown()) {
        log.info("Instance is shutting down");
        return;
      }

      if (continueImmediately) {
        continue;
      }

      // wait a few seconds
      try {
        Thread.sleep(2500);
      } catch (InterruptedException e) {
        return;
      }
    }
  }
}
