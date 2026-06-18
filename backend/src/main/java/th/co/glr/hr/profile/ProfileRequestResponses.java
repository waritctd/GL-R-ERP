package th.co.glr.hr.profile;

import java.util.List;

public final class ProfileRequestResponses {
    private ProfileRequestResponses() {
    }

    public record ProfileRequestsResponse(List<ProfileRequestDto> profileRequests) {
    }

    public record ProfileRequestResponse(ProfileRequestDto profileRequest) {
    }
}
