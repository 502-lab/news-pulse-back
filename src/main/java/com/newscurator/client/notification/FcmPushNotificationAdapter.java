package com.newscurator.client.notification;

import com.google.firebase.FirebaseApp;
import com.google.firebase.messaging.FirebaseMessaging;
import com.google.firebase.messaging.FirebaseMessagingException;
import com.google.firebase.messaging.Message;
import com.google.firebase.messaging.MessagingErrorCode;
import com.google.firebase.messaging.Notification;
import com.newscurator.exception.FcmUnregisteredException;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FcmPushNotificationAdapter implements PushNotificationPort {

    private static final Logger log = LoggerFactory.getLogger(FcmPushNotificationAdapter.class);

    private final Optional<FirebaseApp> firebaseApp;

    public FcmPushNotificationAdapter(Optional<FirebaseApp> firebaseApp) {
        this.firebaseApp = firebaseApp;
    }

    @Override
    public void send(String token, String title, String body) throws FcmUnregisteredException {
        FirebaseApp app = firebaseApp.orElseThrow(
                () -> new IllegalStateException("Firebase not initialized — FIREBASE_SERVICE_ACCOUNT_JSON 미설정"));

        Message message = Message.builder()
                .setToken(token)
                .setNotification(Notification.builder()
                        .setTitle(title)
                        .setBody(body)
                        .build())
                .build();

        try {
            FirebaseMessaging.getInstance(app).send(message);
        } catch (FirebaseMessagingException e) {
            MessagingErrorCode code = e.getMessagingErrorCode();
            if (code == MessagingErrorCode.UNREGISTERED || code == MessagingErrorCode.INVALID_ARGUMENT) {
                throw new FcmUnregisteredException(token);
            }
            throw new RuntimeException("FCM send failed: " + e.getErrorCode(), e);
        }
    }
}
