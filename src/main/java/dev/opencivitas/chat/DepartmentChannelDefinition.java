package dev.opencivitas.chat;

import java.util.List;

public record DepartmentChannelDefinition(
        ChatChannel channel,
        List<String> jobIds,
        List<String> officeIds
) {
    public DepartmentChannelDefinition {
        jobIds = List.copyOf(jobIds);
        officeIds = List.copyOf(officeIds);
    }
}
