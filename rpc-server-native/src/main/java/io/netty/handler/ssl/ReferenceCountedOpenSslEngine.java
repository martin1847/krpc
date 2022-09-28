package io.netty.handler.ssl;

import java.nio.ByteBuffer;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLEngineResult;
import javax.net.ssl.SSLEngineResult.HandshakeStatus;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSession;

import io.netty.util.ReferenceCounted;

/**
 * just for override Graalvm runtime build
 */
public class ReferenceCountedOpenSslEngine extends SSLEngine implements ReferenceCounted, ApplicationProtocolAccessor {


    static {
        System.out.println("[rpc-server-native] Netty ReferenceCountedOpenSslEngine Not Support!!!");
    }


    @Override
    public String getNegotiatedApplicationProtocol() {
        throw new UnsupportedOperationException();
    }

    @Override
    public int refCnt() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceCounted retain() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceCounted retain(int increment) {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceCounted touch() {
        throw new UnsupportedOperationException();
    }

    @Override
    public ReferenceCounted touch(Object hint) {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean release() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean release(int decrement) {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLEngineResult wrap(ByteBuffer[] srcs, int offset, int length, ByteBuffer dst) throws SSLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLEngineResult unwrap(ByteBuffer src, ByteBuffer[] dsts, int offset, int length) throws SSLException {
        throw new UnsupportedOperationException();
    }

    @Override
    public Runnable getDelegatedTask() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void closeInbound() throws SSLException {

    }

    @Override
    public boolean isInboundDone() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void closeOutbound() {

    }

    @Override
    public boolean isOutboundDone() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getSupportedCipherSuites() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getEnabledCipherSuites() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnabledCipherSuites(String[] suites) {

    }

    @Override
    public String[] getSupportedProtocols() {
        throw new UnsupportedOperationException();
    }

    @Override
    public String[] getEnabledProtocols() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnabledProtocols(String[] protocols) {

    }

    @Override
    public SSLSession getSession() {
        return null;
    }

    @Override
    public void beginHandshake() throws SSLException {

    }

    @Override
    public HandshakeStatus getHandshakeStatus() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setUseClientMode(boolean mode) {

    }

    @Override
    public boolean getUseClientMode() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setNeedClientAuth(boolean need) {

    }

    @Override
    public boolean getNeedClientAuth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setWantClientAuth(boolean want) {

    }

    @Override
    public boolean getWantClientAuth() {
        throw new UnsupportedOperationException();
    }

    @Override
    public void setEnableSessionCreation(boolean flag) {

    }

    @Override
    public boolean getEnableSessionCreation() {
        throw new UnsupportedOperationException();
    }
}