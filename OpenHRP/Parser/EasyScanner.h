/*! @file
  @brief Header file of text scanner class
  @author S.NAKAOKA
*/


#ifndef EASYSCANNER_H_INCLUDED
#define EASYSCANNER_H_INCLUDED

#include <map>
#include <string>
#include <vector>
#include <boost/shared_ptr.hpp>


//! Text scanner class
class EasyScanner {

 public:

    class Endl {
	//int dummy;
    };

    class Exception {
    public:
	std::string message;
	std::string filename;
	int lineNumber;
	std::string getFullMessage();
    };

    enum TokenType {
	T_NONE = 0, T_SPACE, T_ALPHABET, T_INTEGER, T_DOUBLE, T_WORD,
	T_STRING, T_SIGLUM, T_LF, T_EOF
    };

    typedef std::map<std::string, int> SymbolMap;
    typedef std::pair<std::string, int> SymbolPair;
    typedef boost::shared_ptr<SymbolMap> SymbolMapPtr;

    Endl endl;

    EasyScanner();
    EasyScanner(std::string filename);
    EasyScanner(const EasyScanner& scanner, bool copy_text = false);
    ~EasyScanner();

    void putSymbols();

    inline void registerSymbol(int id, const std::string& symbol) {
	symbols->insert(SymbolPair(symbol, id));
    }

    inline int  getSymbolID(const std::string& symbol) {
	SymbolMap::iterator p = symbols->find(symbol);
	return (p != symbols->end()) ? p->second : 0;
    }

    /// if 0, comment is disabled
    void setCommentChar(char cc);

    void setLineOriented(bool on);
    void setQuoteChar(char qc);
    void setWhiteSpaceChar(char ws);

    void loadFile(const std::string& filename);

    void setText(const char* text, int len);

    void setLineNumberOffset(int offset);

    void setDefaultErrorMessage(const std::string& message){
	defaultErrorMessage = message;
    }

    void moveToHead();

    int  readToken();

    void toLower();

    bool readDouble();
    bool readInt();
    bool readChar();
    bool readChar(int chara);
    int  peekChar();

    /**
       In contrast to readString(),
       this function does not recognize siglums except '_' as a part of a word.
    */
    inline bool readWord() {
	skipSpace();
	return readWord0();
    }

    /**
       In contrast to readWord(),
       this function allows a string to include siglums such as !,",#,$,%,&,...
    */
    inline bool readString(const int delimiterChar = ',') {
      skipSpace();
      return readString0(delimiterChar);
    }

    bool readString(const char* str);

    inline bool readString(const std::string& str) {
	return readString(str.c_str());
    }

    bool readQuotedString(bool allowNoQuotedWord = false);

    bool readUnquotedTextBlock();

    bool readSymbol();
    bool readSymbol(int id);

    inline bool isEOF(){
	skipSpace();
	return (*text == '\0');
    }

    /// reading a line feed
    inline bool readLF() {
	skipSpace();
	return readLF0();
    }

    inline bool readLFEOF() {
    	skipSpace();
    	return readLF0() ? true : (*text == '\0');
    }

    bool checkLF();

    bool readLine();
    bool skipLine();
    bool skipBlankLines();

    void skipSpace();

    void throwException(const char* message);

    /**
       The exception version of readInt().
       \return Scanned int value.
    */
    int readIntEx(const char* message = 0) {
	if(!readInt()) throwException(message);
	return intValue;
    }
    /**
       The exception version of readDouble().
       \return Scanned double value.
    */
    double readDoubleEx(const char* message = 0) {
	if(!readDouble()) throwException(message);
	return doubleValue;
    }
    /**
       The exception version of readChar().
       \return Scanned char value.
    */
    int readCharEx(const char* message = 0) {
	if(!readChar())
		throwException(message);
	return charValue;
    }
    /**
       The exception version of readChar().
    */
    void readCharEx(int chara, const char* message = 0) {
	if(!readChar(chara)) throwException(message);
    }
    /**
       The exception version of readWord().
       \return Scanned word string.
    */
    std::string readWordEx(const char* message = 0) {
	if(!readWord()) throwException(message);
	return stringValue;
    }

    /**
       The exception version of readString().
       \return Scanned word string.
    */
    std::string readStringEx(const char* message = 0) {
	if(!readString()) throwException(message);
	return stringValue;
    }

    std::string readQuotedStringEx(const char* message = 0) {
	if(!readQuotedString()) throwException(message);
	return stringValue;
    }
    /**
       The exception version of readSymbol().
       \return ID of the scanned symbol.
    */
    int readSymbolEx(const char* message = 0) {
	if(!readSymbol()) throwException(message);
	return symbolValue;
    }
    /**
       The exception version of readLF().
    */
    void readLFex(const char* message = 0) {
	if(!readLF()) throwException(message);
    }

	void readLFEOFex(const char* message = 0) {
		if(!readLFEOF()) throwException(message);
    }



    int intValue;
    double doubleValue;
    std::string stringValue;
    char charValue;
    int symbolValue;

    std::string defaultErrorMessage;
    int lineNumber;

    char* text;

    std::string filename;

 private:
    void init();
    int extractQuotedString();

    inline void skipToLineEnd();
    bool readLF0();
    bool readWord0();
    bool readString0(const int delimiterChar);
    
    char* textBuf;
    int size;
    char* textBufEnd;
    int lineNumberOffset;
    int commentChar;
    int quoteChar;
    bool isLineOriented;

    std::vector<int> whiteSpaceChars;

    SymbolMapPtr symbols;

    friend EasyScanner& operator>>(EasyScanner& scanner, double& value);
    friend EasyScanner& operator>>(EasyScanner& scanner, int& value);
    friend EasyScanner& operator>>(EasyScanner& scanner, const char* matchString);
    friend EasyScanner& operator>>(EasyScanner& scanner, char matchChar);
    friend EasyScanner& operator>>(EasyScanner& scanner, std::string& str);
    friend EasyScanner& operator>>(EasyScanner& scanner, EasyScanner::Endl endl);

};


EasyScanner& operator>>(EasyScanner& scanner, double& value);
EasyScanner& operator>>(EasyScanner& scanner, int& value);
EasyScanner& operator>>(EasyScanner& scanner, const char* matchString);
EasyScanner& operator>>(EasyScanner& scanner, char matchChar);
EasyScanner& operator>>(EasyScanner& scanner, std::string& str);
EasyScanner& operator>>(EasyScanner& scanner, EasyScanner::Endl endl);


typedef boost::shared_ptr<EasyScanner> EasyScannerPtr;


#endif
