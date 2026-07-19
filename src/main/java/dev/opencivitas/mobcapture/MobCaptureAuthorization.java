package dev.opencivitas.mobcapture;

public record MobCaptureAuthorization(
        MobCaptureResult result,
        long auditId,
        long balanceCents,
        String jobId
) {
    public static MobCaptureAuthorization failed(MobCaptureResult result, long balanceCents) {
        return new MobCaptureAuthorization(result, 0, balanceCents, null);
    }

    public static MobCaptureAuthorization success(long auditId, long balanceCents, String jobId) {
        return new MobCaptureAuthorization(MobCaptureResult.SUCCESS, auditId, balanceCents, jobId);
    }
}
