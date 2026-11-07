package com.vedashree.game.analytics;

import com.vedashree.game.AppContext;
import jakarta.servlet.annotation.WebListener;
import jakarta.servlet.http.HttpSessionEvent;
import jakarta.servlet.http.HttpSessionIdListener;
import jakarta.servlet.http.HttpSessionListener;

/** Feeds container session events into the {@link LiveSessionRegistry}. */
@WebListener
public class SessionLifecycleListener implements HttpSessionListener, HttpSessionIdListener {
    @Override
    public void sessionCreated(HttpSessionEvent event) {
        AppContext app = AppContext.from(event.getSession().getServletContext());
        if (app != null) {
            app.sessions().onCreated(event.getSession());
        }
    }

    @Override
    public void sessionDestroyed(HttpSessionEvent event) {
        AppContext app = AppContext.from(event.getSession().getServletContext());
        if (app != null) {
            app.sessions().onDestroyed(event.getSession());
        }
    }

    @Override
    public void sessionIdChanged(HttpSessionEvent event, String oldSessionId) {
        AppContext app = AppContext.from(event.getSession().getServletContext());
        if (app != null) {
            app.sessions().onIdChanged(event.getSession(), oldSessionId);
        }
    }
}
