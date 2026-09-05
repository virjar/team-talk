package com.virjar.tk.shared.agent

import com.virjar.tk.shared.client.DeploymentIdentity

internal val TEST_AGENT_DEPLOYMENT_IDENTITY: DeploymentIdentity = DeploymentIdentity.from(
    tcpHost = "im.test.example",
    tcpPort = 5100,
    serverUrl = "https://files.test.example/api",
)
