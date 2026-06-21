package com.newscurator.client.notification;

import com.newscurator.exception.FcmUnregisteredException;

public interface PushNotificationPort {

    void send(String token, String title, String body) throws FcmUnregisteredException;
}
