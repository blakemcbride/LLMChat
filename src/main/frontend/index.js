
/* global Utils, Server */

'use strict';

Utils.afterComponentsLoaded(function () {
    if (SystemInfo.backendUrl) {
        // explicit backend URL
        Server.setURL(SystemInfo.backendUrl);
    } else if (window.location.protocol === "file:") {
        //  electron desktop frontend
        //  Electron: LLMChat port block base 8200 -> back end 8201
        Server.setURL('http://localhost:8201');
    } else if (window.location.protocol === "http:" && window.location.port >= 8000) {
        //  Development environment
        //  Development: back end runs one port above the front-end dev server
        Server.setURL('http://' + window.location.hostname + ':' + (Number(window.location.port) + 1));
    } else {
        //  Production environment with front-end & back-end as one unit
        let url = Utils.getAppUrl();
        Server.setURL(url);
    }

    Utils.forceASCII = false;  // Force all text entry to ASCII (see Utils.forceASCII)

    // No login: open directly on the Memory Chat screen.
    Utils.loadPage('screens/MemoryChat/MemoryChat');
});


(function () {
    Utils.useComponent('Popup');
    Utils.useComponent('CheckBox');
    Utils.useComponent('DateInput');
    Utils.useComponent('DropDown');
    Utils.useComponent('DurationInput');
    Utils.useComponent('ListBox');
    Utils.useComponent('NumericInput');
    Utils.useComponent('PushButton');
    Utils.useComponent('RadioButton');
    Utils.useComponent('TextboxInput');
    Utils.useComponent('TextInput');
    Utils.useComponent('TextLabel');
    Utils.useComponent('TimeInput');
    Utils.useComponent('FileUpload');
    Utils.useComponent('NativeDateInput');
    Utils.useComponent('Picture');
})();

