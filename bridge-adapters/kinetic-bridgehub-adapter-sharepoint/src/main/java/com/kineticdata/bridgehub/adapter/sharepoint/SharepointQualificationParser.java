package com.kineticdata.bridgehub.adapter.sharepoint;

import com.kineticdata.bridgehub.adapter.QualificationParser;

public class SharepointQualificationParser extends QualificationParser {
    public String encodeParameter(String name, String value) {
        return value;
    }
}
