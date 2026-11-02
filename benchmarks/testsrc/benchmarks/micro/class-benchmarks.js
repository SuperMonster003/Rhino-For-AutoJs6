// AutoJs6 class syntax (ROADMAP P2.5): ES2015 classes against the same objects written as
// constructor functions and prototypes. Each benchmark function runs 1000 operations.

class PointC {
    constructor(x, y) {
        this.x = x;
        this.y = y;
    }
    sum() {
        return this.x + this.y;
    }
}

class Point3C extends PointC {
    constructor(x, y, z) {
        super(x, y);
        this.z = z;
    }
    sum() {
        return super.sum() + this.z;
    }
}

function PointF(x, y) {
    this.x = x;
    this.y = y;
}
PointF.prototype.sum = function () {
    return this.x + this.y;
};

function Point3F(x, y, z) {
    PointF.call(this, x, y);
    this.z = z;
}
Point3F.prototype = Object.create(PointF.prototype);
Point3F.prototype.constructor = Point3F;
Point3F.prototype.sum = function () {
    return PointF.prototype.sum.call(this) + this.z;
};

function classNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new PointC(i, 1);
    }
    return p;
}

function functionNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new PointF(i, 1);
    }
    return p;
}

// Function expressions: the compiler has no direct call for these, as for a class constructor
// (a function statement in the same script gets one, Q51).
var PointV = function (x, y) {
    this.x = x;
    this.y = y;
};

var PointS = function (x, y) {
    'use strict';
    this.x = x;
    this.y = y;
};

function functionExprNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new PointV(i, 1);
    }
    return p;
}

function strictFunctionExprNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new PointS(i, 1);
    }
    return p;
}

function classDerivedNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new Point3C(i, 1, 2);
    }
    return p;
}

function functionDerivedNew() {
    var p;
    for (var i = 0; i < 1000; i++) {
        p = new Point3F(i, 1, 2);
    }
    return p;
}

var pointC = new PointC(1, 2);
var pointF = new PointF(1, 2);
var point3C = new Point3C(1, 2, 3);
var point3F = new Point3F(1, 2, 3);

function classCall() {
    var s = 0;
    for (var i = 0; i < 1000; i++) {
        s += pointC.sum();
    }
    return s;
}

function functionCall() {
    var s = 0;
    for (var i = 0; i < 1000; i++) {
        s += pointF.sum();
    }
    return s;
}

function classSuperCall() {
    var s = 0;
    for (var i = 0; i < 1000; i++) {
        s += point3C.sum();
    }
    return s;
}

function functionSuperCall() {
    var s = 0;
    for (var i = 0; i < 1000; i++) {
        s += point3F.sum();
    }
    return s;
}

var settled = Promise.resolve(1);

function awaitSettled() {
    return (async function () {
        for (var i = 0; i < 1000; i++) {
            await settled;
        }
    })();
}

function thenSettled() {
    var i = 0;
    function step() {
        if (++i < 1000) {
            return settled.then(step);
        }
    }
    return settled.then(step);
}
