package com.caseware.templateupdates.domain;

public sealed interface Reason permits TemplatePublished, InitialLoad {
    Priority priority();
}
