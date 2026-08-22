package com.e2eechat.core.protocol;

import com.e2eechat.core.models.Message;
import com.e2eechat.core.models.MessageBuilder;

import java.security.PrivateKey;
import java.security.Signature;

public class MessageSigner {
    
    public static Message sign(Message msg, PrivateKey key) throws Exception {
        byte[] canonicalBytes = msg.canonicalBytesForSigning();
        
        Signature signature = Signature.getInstance("SHA256withRSA");
        signature.initSign(key);
        signature.update(canonicalBytes);
        byte[] sigBytes = signature.sign();
        
        return new MessageBuilder()
                .setType(msg.getType())
                .setSenderId(msg.getSenderId())
                .setReceiverId(msg.getReceiverId())
                .setPayload(msg.getPayload())
                .setMessageId(msg.getMessageId())
                .setTimestamp(msg.getTimestamp())
                .setIv(msg.getIv())
                .setProtocolVersion(msg.getProtocolVersion())
                .setSignature(sigBytes)
                .build();
    }
}
