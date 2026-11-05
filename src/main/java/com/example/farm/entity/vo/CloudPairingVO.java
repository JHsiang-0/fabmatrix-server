package com.example.farm.entity.vo;

public record CloudPairingVO(String pairingId, String state, String qrContent, long expiresAt) {
    @Override public String toString() { return "CloudPairingVO[" + state + "]"; }
}
