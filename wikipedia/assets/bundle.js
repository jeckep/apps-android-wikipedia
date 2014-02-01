(function e(t,n,r){function s(o,u){if(!n[o]){if(!t[o]){var a=typeof require=="function"&&require;if(!u&&a)return a(o,!0);if(i)return i(o,!0);throw new Error("Cannot find module '"+o+"'")}var f=n[o]={exports:{}};t[o][0].call(f.exports,function(e){var n=t[o][1][e];return s(n?n:e)},f,f.exports,e,t,n,r)}return n[o].exports}var i=typeof require=="function"&&require;for(var o=0;o<r.length;o++)s(r[o]);return s})({1:[function(require,module,exports){
function Bridge() {
}

var eventHandlers = {};

// This is called directly from Java
window.handleMessage = function( type, msgPointer ) {
    var that = this;
    var payload = JSON.parse( marshaller.getPayload( msgPointer ) );
    if ( eventHandlers.hasOwnProperty( type ) ) {
        eventHandlers[type].forEach( function( callback ) {
            callback.call( that, payload );
        } );
    }
};

Bridge.prototype.registerListener = function( messageType, callback ) {
    if ( eventHandlers.hasOwnProperty( messageType ) ) {
        eventHandlers[messageType].push( callback );
    } else {
        eventHandlers[messageType] = [ callback ];
    }
};

Bridge.prototype.sendMessage = function( messageType, payload ) {
    var messagePack = { type: messageType, payload: payload };
    var ret = window.prompt( JSON.stringify( messagePack) );
    if ( ret ) {
        return JSON.parse( ret );
    }
};

module.exports = new Bridge();
// FIXME: Move this to somwehere else, eh?
window.onload = function() {
    module.exports.sendMessage( "DOMLoaded", {} );
};
},{}],2:[function(require,module,exports){
var bridge = require('./bridge');

var actionHandlers = {
    "edit_section": function( el, event ) {
        bridge.sendMessage( 'editSectionClicked', { sectionID: el.getAttribute( 'data-id' ) } );
        event.preventDefault();
    }
};

document.onclick = function() {
    if ( event.target.tagName === "A" ) {
        if ( event.target.hasAttribute( "data-action" ) ) {
            var action = event.target.getAttribute( "data-action" );
            actionHandlers[ action ]( event.target, event );
        } else {
            bridge.sendMessage( 'linkClicked', { href: event.target.getAttribute( "href" ) });
            event.preventDefault();
        }
    }
};
},{"./bridge":1}],3:[function(require,module,exports){
var bridge = require("./bridge");
bridge.registerListener( "displayAttribution", function( payload ) {
    var lastUpdatedA = document.getElementById( "lastupdated" );
    lastUpdatedA.innerText = payload.historyText;
    lastUpdatedA.href = payload.historyTarget;
    var licenseText = document.getElementById( "licensetext" );
    licenseText.innerHTML = payload.licenseHTML;
});

bridge.registerListener( "requestImagesList", function () {
    var imageURLs = [];
    var images = document.querySelectorAll( "img" );
    for ( var i = 0; i < images.length; i++ ) {
        imageURLs.push( images[i].src );
    }
    bridge.sendMessage( "imagesListResponse", { "images": imageURLs });
} );
},{"./bridge":1}],4:[function(require,module,exports){
var bridge = require("./bridge");
var transformer = require("./transformer");

bridge.registerListener( "displayLeadSection", function( payload ) {
    // This might be a refresh! Clear out all contents!
    document.getElementById( "content" ).innerHTML = "";

    var title = document.createElement( "h1" );
    title.textContent = payload.title;
    title.id = "heading_" + payload.section.id;
    document.getElementById( "content" ).appendChild( title );

    var content = document.createElement( "div" );
    content.innerHTML = payload.section.text;
    content.id = "#content_block_0";
    content = transformer.transform( "leadSection", content );
    content = transformer.transform( "section", content );
    document.getElementById( "content" ).appendChild( content );

    document.getElementById( "loading_sections").className = "loading";
});

function elementsForSection( section ) {
    var heading = document.createElement( "h" + ( section.toclevel + 1 ) );
    heading.textContent = section.line;
    heading.id = "heading_" + section.id;
    heading.setAttribute( 'data-id', section.id );

    var editButton = document.createElement( "a" );
    editButton.setAttribute( 'data-id', section.id );
    editButton.setAttribute( 'data-action', "edit_section" );
    editButton.className = "edit_section_button";
    heading.appendChild( editButton );

    var content = document.createElement( "div" );
    content.innerHTML = section.text;
    content.id = "content_block_" + section.id;
    content = transformer.transform( "section", content );

    return [ heading, content ];
}

bridge.registerListener( "displaySection", function ( payload ) {
    var contentWrapper = document.getElementById( "content" );

    elementsForSection( payload.section ).forEach( function( element ) {
        contentWrapper.appendChild( element );
    });
    if ( !payload.isLast ) {
        bridge.sendMessage( "requestSection", { index: payload.index + 1 } );
    } else {
        document.getElementById( "loading_sections").className = "";
    }
});

bridge.registerListener( "startSectionsDisplay", function() {
    bridge.sendMessage( "requestSection", { index: 1 } );
});

bridge.registerListener( "scrollToSection", function ( payload ) {
    var el = document.getElementById( "heading_" + payload.sectionID);
    // Make sure there's exactly as much space on the left as on the top.
    // The 48 accounts for the search bar
    var scrollY = el.offsetTop - 48 - el.offsetLeft;
    window.scrollTo(0, scrollY);
});

},{"./bridge":1,"./transformer":5}],5:[function(require,module,exports){
function Transformer() {
}

var transforms = {};

Transformer.prototype.register = function( transform, fun ) {
    if ( transform in transforms ) {
        transforms[transform].append( fun );
    } else {
        transforms[transform] = [ fun ];
    }
};

Transformer.prototype.transform = function( transform, element ) {
    var functions = transforms[transform];
    for ( var i = 0; i < functions.length; i++ ) {
        element = functions[i](element);
    }
    return element;
};

module.exports = new Transformer();

},{}],6:[function(require,module,exports){
var bridge = require("./bridge");
var transformer = require("./transformer");

// Move infobox to the bottom of the lead section
transformer.register( "leadSection", function( leadContent ) {
    var infobox = leadContent.querySelector( "table.infobox" );
    if ( infobox ) {
        infobox.parentNode.removeChild( infobox );
        var pTags = leadContent.getElementsByTagName( "p" );
        if ( pTags.length ) {
            pTags[0].appendChild( infobox );
        } else {
            leadContent.appendChild( infobox );
        }
    }
    return leadContent;
} );

// Use locally cached images as fallback in saved pages
transformer.register( "section", function( content ) {
    var images = content.querySelectorAll( "img" );
    function onError() {
        var img = event.target;
        // Only work on http or https URLs. If we do not have this check, we might go on an infinte loop
        if ( img.src.substring( 0, 4 ) === "http" )  {
            // if it is already not a file URL!
            var resp = bridge.sendMessage( "imageUrlToFilePath", { "imageUrl": img.src } );
            console.log( "new filepath is " + resp.filePath );
            img.src = "file://" + resp.filePath;
        }
    }
    for ( var i = 0; i < images.length; i++ ) {
        images[i].onerror = onError;
    }
    return content;
} );

},{"./bridge":1,"./transformer":5}]},{},[3,5,6,1,2,4])